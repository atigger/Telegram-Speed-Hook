package Telegram.Speed.Hook;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.util.AtomicFile;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;


final class HookDiagnostics {
    static final String RELATIVE_PATH = "files/tg_speed_hook/status.json";
    private static final JSONObject state = new JSONObject();
    private static DiagnosticStore store;
    private static long sequence;
    private static final Set<String> shownToasts = new HashSet<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Runnable write = HookDiagnostics::persist;
    private static Handler worker;
    private static volatile boolean recording;
    private static volatile String captureId = "";
    private static volatile Runnable resetMonitor;
    private static AtomicFile file;
    private static Resources strings;
    private static WeakReference<Activity> foreground = new WeakReference<>(null);

    static synchronized void start(Context context) throws Exception {
        File directory = new File(context.getFilesDir(), "tg_speed_hook");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("diagnostic directory unavailable");
        }
        file = new AtomicFile(new File(directory, "status.json"));
        store = new DiagnosticStore(directory);
        String retainedCapture = store.captureId();
        captureId = store.acknowledged(retainedCapture) ? retainedCapture : "";
        state.put("captureId", captureId);
        HandlerThread thread = new HandlerThread("tg-speed-diagnostics");
        thread.start();
        worker = new Handler(thread.getLooper());
        recording = store.recording();
        worker.post(new Runnable() {
            @Override
            public void run() {
                if (!refreshCapture()) {
                    worker.postDelayed(this, 2000);
                    return;
                }
                boolean enabled = store.recording();
                if (enabled && !recording) {
                    record("RECORDING_START: only requests observed after enabling are counted");
                }
                recording = enabled;
                scheduleWrite();
                worker.postDelayed(this, 2000);
            }
        });
        state.put("schema", 1);
        state.put("moduleVersion", BuildConfig.VERSION_NAME);
        state.put("pid", Process.myPid());
        state.put("startedAt", System.currentTimeMillis());
        state.put("bootId", readProc("/proc/sys/kernel/random/boot_id"));
        state.put("processStart", processStart(readProc("/proc/self/stat")));
        state.put("phase", "starting");
        state.put("budgetRequired", true);
        try {
            strings = context.getPackageManager().getResourcesForApplication(MainHook.moduleInfo);
        } catch (Throwable error) {
            Log.w("TgSpeedHook", "Toast resources unavailable", error);
        }
        ModernHooks.hookAllMethods(Activity.class, "onResume", new HookCallback() {
            @Override
            protected void after(Invocation param) {
                Activity activity = (Activity) param.thisObject;
                if ("org.telegram.messenger".equals(activity.getPackageName())) {
                    foreground = new WeakReference<>(activity);
                    main.postDelayed(HookDiagnostics::showToast, 500);
                }
            }
        });
        ModernHooks.hookAllMethods(Activity.class, "onPause", new HookCallback() {
            @Override
            protected void after(Invocation param) {
                if (foreground.get() == param.thisObject) {
                    foreground.clear();
                }
            }
        });
        record("SESSION_START module=" + BuildConfig.VERSION_NAME);
    }

    static Handler worker() {
        return worker;
    }

    static boolean recording() {
        return recording;
    }

    static String captureId() {
        return captureId;
    }

    static void registerMonitorReset(Runnable reset) {
        resetMonitor = reset;
    }

    private static boolean refreshCapture() {
        try {
            String requested = store.captureId();
            if (requested.equals(captureId)) {
                return true;
            }
            recording = false;
            store.clear();
            Runnable reset = resetMonitor;
            if (reset != null) {
                reset.run();
            }
            file.delete();
            store.acknowledge(requested);
            synchronized (HookDiagnostics.class) {
                state.remove("metrics");
                state.put("captureId", requested);
                state.put("captureStartedAt", System.currentTimeMillis());
                captureId = requested;
            }
            recording = store.recording();
            record("CAPTURE_START: counters reset; only newly observed requests are counted");
            return true;
        } catch (Throwable error) {
            recording = false;
            Log.w("TgSpeedHook", "Capture reset failed; recording paused", error);
            return false;
        }
    }

    static synchronized void budgetRequired(boolean required) {
        try {
            state.put("budgetRequired", required);
        } catch (Exception error) {
            Log.w("TgSpeedHook", "Diagnostic state unavailable", error);
        }
    }

    static synchronized void record(String message) {
        if (worker == null) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            JSONObject entry = new JSONObject().put("time", now).put("type", "event")
                    .put("captureId", captureId)
                    .put("id", state.optLong("startedAt") + "-" + (++sequence))
                    .put("message", message);
            worker.post(() -> append(entry));
            if (message.startsWith("Telegram ")) {
                state.put("telegram", message);
            } else if (message.startsWith("PROFILE ")) {
                state.put("profile", message.substring(8));
            } else if (message.startsWith("VIDEO_PROFILE ")) {
                state.put("videoEnabled", message.endsWith("on"));
            } else if (message.startsWith("VIDEO_READY")) {
                state.put("videoState", "ready");
            } else if (message.startsWith("VIDEO_UNAVAILABLE") || message.startsWith("VIDEO_DISABLED")) {
                state.put("videoState", "unavailable");
            } else if (message.startsWith("VIDEO_PRIORITY_APPLIED")) {
                if (!"parameters_applied".equals(state.optString("videoState"))) {
                    state.put("videoState", "priority_applied");
                }
            } else if (message.startsWith("VIDEO_POSITION_REQUESTED") && message.endsWith("priority=true")) {
                if (!"parameters_applied".equals(state.optString("videoState"))) {
                    state.put("videoState", "position_priority_requested");
                }
            } else if (message.startsWith("PROFILE_READ_FAILED")) {
                state.put("phase", "profile_error");
            } else if (message.startsWith("PROFILE_ORIGINAL")
                    && !"profile_error".equals(state.optString("phase"))) {
                state.put("phase", "original");
            } else if (message.startsWith("HOOK_READY")
                    && "starting".equals(state.optString("phase"))) {
                state.put("phase", "ready");
            } else if (message.startsWith("PARAMS_APPLIED") || message.startsWith("BUDGET_APPLIED")) {
                if (message.contains("mode=video") || message.contains("profile=video")) {
                    state.put("videoState", "parameters_applied");
                }
                state.put(message.startsWith("PARAMS_APPLIED") ? "paramsApplied" : "budgetApplied", true);
                if (!"disabled".equals(state.optString("phase"))) {
                    boolean applied = state.optBoolean("paramsApplied")
                            && (state.optBoolean("budgetApplied") || !state.optBoolean("budgetRequired"));
                    state.put("phase", applied ? "applied" : "partial");
                }
            } else if (message.startsWith("UNSUPPORTED")) {
                state.put("phase", "unsupported");
            } else if (message.startsWith("POLICY_DISABLED")) {
                state.put("phase", "disabled");
            } else if (message.startsWith("MONITOR_READY")) {
                state.put("monitorAvailable", true);
            } else if (message.startsWith("MONITOR_UNAVAILABLE")) {
                state.put("monitorAvailable", false);
            }
            state.put("updatedAt", now);
            scheduleWrite();
            main.post(HookDiagnostics::showToast);
        } catch (Throwable error) {
            Log.w("TgSpeedHook", "Diagnostic event unavailable", error);
        }
    }

    static synchronized void metrics(JSONObject metrics, boolean sample) {
        if (!captureId.equals(metrics.optString("captureId"))) {
            return;
        }
        try {
            state.put("metrics", metrics);
            if (sample && worker != null) {
                JSONObject entry = new JSONObject(metrics.toString()).put("type", "sample")
                        .put("id", state.optLong("startedAt") + "-" + (++sequence));
                worker.post(() -> append(entry));
            }
            scheduleWrite();
        } catch (Throwable error) {
            Log.w("TgSpeedHook", "Diagnostic metrics unavailable", error);
        }
    }

    private static void append(JSONObject entry) {
        try {
            if (!refreshCapture() || !captureId.equals(entry.optString("captureId"))) {
                return;
            }
            store.append(entry);
        } catch (Throwable error) {
            Log.w("TgSpeedHook", "Local log write failed", error);
        }
    }

    private static void scheduleWrite() {
        if (worker != null) {
            worker.removeCallbacks(write);
            worker.postDelayed(write, 100);
        }
    }

    private static void persist() {
        if (!refreshCapture()) {
            return;
        }
        store.cleanup();
        if (!store.recording()) {
            file.delete();
            return;
        }
        FileOutputStream output = null;
        try {
            byte[] bytes;
            synchronized (HookDiagnostics.class) {
                state.put("recording", store.recording());
                bytes = state.toString().getBytes(StandardCharsets.UTF_8);
            }
            output = file.startWrite();
            output.write(bytes);
            file.finishWrite(output);
        } catch (Throwable error) {
            if (output != null) {
                file.failWrite(output);
            }
            Log.w("TgSpeedHook", "Diagnostic file unavailable", error);
        }
    }

    private static synchronized void showToast() {
        Activity activity = foreground.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || strings == null) {
            return;
        }
        String phase = state.optString("phase");
        int resource;
        String category;
        switch (phase) {
            case "ready":
            case "partial":
                resource = R.string.toast_ready;
                category = "ready";
                break;
            case "applied":
                resource = R.string.toast_applied;
                category = "applied";
                break;
            case "original":
                resource = R.string.toast_original;
                category = "original";
                break;
            case "profile_error":
            case "unsupported":
            case "disabled":
                resource = R.string.toast_failed;
                category = "failed";
                break;
            default:
                return;
        }
        if (shownToasts.contains(category)) {
            return;
        }
        try {
            Toast.makeText(activity, strings.getString(resource), Toast.LENGTH_LONG).show();
            shownToasts.add(category);
        } catch (Throwable error) {
            Log.w("TgSpeedHook", "Diagnostic toast unavailable", error);
        }
    }

    static String processStart(String stat) {
        int end = stat.lastIndexOf(')');
        String[] fields = stat.substring(end + 1).trim().split("\\s+");
        return end > 0 && fields.length > 19 ? fields[19] : "";
    }

    private static String readProc(String path) {
        try (FileInputStream input = new FileInputStream(path);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1 && output.size() < 4096) {
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
        } catch (Exception error) {
            return "";
        }
    }
}
