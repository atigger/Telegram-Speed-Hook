package Telegram.Speed.Hook;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.IOException;
import java.io.StringReader;
import java.util.concurrent.CopyOnWriteArraySet;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public final class ModuleApplication extends Application {
    private static final Object lock = new Object();
    private static final CopyOnWriteArraySet<Runnable> listeners = new CopyOnWriteArraySet<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static volatile SharedPreferences remote;
    private static volatile boolean failed;
    private static XposedService activeService;
    private static long generation;

    @Override
    public void onCreate() {
        super.onCreate();
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override
            public void onServiceBind(XposedService service) {
                long attempt;
                synchronized (lock) {
                    activeService = service;
                    attempt = ++generation;
                    remote = null;
                    failed = false;
                }
                notifyState();
                new Thread(() -> connect(service, attempt), "tg-speed-config").start();
            }

            @Override
            public void onServiceDied(XposedService service) {
                synchronized (lock) {
                    if (activeService != service) {
                        return;
                    }
                    activeService = null;
                    generation++;
                    remote = null;
                    failed = true;
                }
                notifyState();
            }
        });
    }

    static SharedPreferences localSettings(Context context) {
        return context.getSharedPreferences(SpeedPolicy.PREFERENCES, Context.MODE_PRIVATE);
    }

    static SharedPreferences preferences() {
        return remote;
    }

    static boolean connectionFailed() {
        return failed;
    }

    static void observe(Runnable listener) {
        listeners.add(listener);
        listener.run();
    }

    static void stopObserving(Runnable listener) {
        listeners.remove(listener);
    }

    private static void notifyState() {
        main.post(() -> {
            for (Runnable listener : listeners) {
                listener.run();
            }
        });
    }

    private void connect(XposedService service, long attempt) {
        try {
            if (service.getApiVersion() < 101
                    || (service.getFrameworkProperties() & XposedService.PROP_CAP_REMOTE) == 0) {
                throw new IOException("Framework does not support modern remote preferences");
            }
            SharedPreferences candidate = service.getRemotePreferences(SpeedPolicy.PREFERENCES);
            SharedPreferences local = localSettings(this);
            if (!candidate.contains("settings_schema")) {
                int profile = local.getInt(SpeedPolicy.PROFILE_KEY, SpeedPolicy.PROFILE_FAST);
                boolean video = local.getBoolean(SpeedPolicy.VIDEO_KEY, false);
                if (local.getInt("settings_schema", 0) != 1) {
                    int[] legacy = readLegacySettings();
                    if (legacy != null) {
                        profile = legacy[0];
                        video = legacy[1] != 0;
                    }
                }
                validateProfile(profile);
                if (!candidate.edit().putInt(SpeedPolicy.PROFILE_KEY, profile)
                        .putBoolean(SpeedPolicy.VIDEO_KEY, video)
                        .putInt("settings_schema", 1).commit()) {
                    throw new IOException("Cannot migrate remote preferences");
                }
            }
            validateProfile(candidate.getInt(SpeedPolicy.PROFILE_KEY, SpeedPolicy.PROFILE_FAST));
            if (candidate.getInt("settings_schema", 0) != 1) {
                throw new IOException("Unsupported remote settings schema");
            }
            synchronized (lock) {
                if (attempt != generation || activeService != service) {
                    return;
                }
                cache(local, candidate.getInt(SpeedPolicy.PROFILE_KEY, SpeedPolicy.PROFILE_FAST),
                        candidate.getBoolean(SpeedPolicy.VIDEO_KEY, false));
                remote = candidate;
                failed = false;
            }
            Log.i("TgSpeedHook", "CONFIG_CONNECTED modern remote preferences");
        } catch (Throwable error) {
            synchronized (lock) {
                if (attempt != generation) {
                    return;
                }
                remote = null;
                failed = true;
            }
            Log.e("TgSpeedHook", "Configuration connection failed", error);
        }
        notifyState();
    }

    static boolean save(Context context, int profile, boolean video) {
        validateProfile(profile);
        synchronized (lock) {
            SharedPreferences selected = remote;
            if (selected == null) {
                return false;
            }
            boolean committed;
            try {
                committed = selected.edit().putInt(SpeedPolicy.PROFILE_KEY, profile)
                        .putBoolean(SpeedPolicy.VIDEO_KEY, video)
                        .putInt("settings_schema", 1).commit();
            } catch (RuntimeException error) {
                committed = false;
                Log.e("TgSpeedHook", "Remote settings save failed", error);
            }
            if (!committed) {
                remote = null;
                failed = true;
                notifyState();
                return false;
            }
            cache(localSettings(context), profile, video);
            return true;
        }
    }

    private static void cache(SharedPreferences local, int profile, boolean video) {
        try {
            if (!local.edit().putInt(SpeedPolicy.PROFILE_KEY, profile)
                    .putBoolean(SpeedPolicy.VIDEO_KEY, video)
                    .putInt("settings_schema", 1).commit()) {
                Log.w("TgSpeedHook", "Local settings backup unavailable");
            }
        } catch (RuntimeException error) {
            Log.w("TgSpeedHook", "Local settings backup unavailable", error);
        }
    }

    private static void validateProfile(int profile) {
        if (profile < SpeedPolicy.PROFILE_ORIGINAL || profile > SpeedPolicy.PROFILE_FAST) {
            throw new IllegalArgumentException("Invalid download profile");
        }
    }

    private static int[] readLegacySettings() throws Exception {
        String suffix = "/prefs/" + BuildConfig.APPLICATION_ID + "/settings.xml";
        RootShell.Result result = RootShell.runShared("for path in /data/misc/apexdata/*"
                + suffix + " /data/misc/*" + suffix + "; do if [ -f \"$path\" ]; then "
                + "cat \"$path\"; exit $?; fi; done; exit 1");
        if (result.exitCode == 1 && result.output.trim().isEmpty()) {
            return null;
        }
        if (result.exitCode != 0 || result.output.contains("<!DOCTYPE")) {
            throw new IOException("Cannot read previous settings; grant Root and reopen the module");
        }
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new StringReader(result.output));
        int profile = SpeedPolicy.PROFILE_FAST;
        boolean video = false;
        boolean map = false;
        for (int event = parser.next(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
            if (event != XmlPullParser.START_TAG) {
                continue;
            }
            if (parser.getDepth() == 1) {
                if (!"map".equals(parser.getName())) {
                    throw new IOException("Invalid previous settings");
                }
                map = true;
            } else if (parser.getDepth() == 2) {
                String name = parser.getAttributeValue(null, "name");
                String value = parser.getAttributeValue(null, "value");
                if (SpeedPolicy.PROFILE_KEY.equals(name) && "int".equals(parser.getName())) {
                    profile = Integer.parseInt(value);
                } else if (SpeedPolicy.VIDEO_KEY.equals(name)
                        && "boolean".equals(parser.getName())) {
                    if (!"true".equals(value) && !"false".equals(value)) {
                        throw new IOException("Invalid previous video setting");
                    }
                    video = Boolean.parseBoolean(value);
                }
            }
        }
        if (!map) {
            throw new IOException("Previous settings are empty");
        }
        validateProfile(profile);
        return new int[]{profile, video ? 1 : 0};
    }
}
