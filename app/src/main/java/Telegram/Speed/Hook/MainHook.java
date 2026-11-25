package Telegram.Speed.Hook;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class MainHook implements IXposedHookLoadPackage, IXposedHookZygoteInit {
    private static final String TAG = "TgSpeedHook";
    private boolean installed;
    static String modulePath;

    @Override
    public void initZygote(StartupParam startupParam) {
        modulePath = startupParam.modulePath;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam load) throws Throwable {
        if (!"org.telegram.messenger".equals(load.packageName)
                || !load.packageName.equals(load.processName)) {
            return;
        }
        XposedBridge.hookMethod(Application.class.getDeclaredMethod("attach", Context.class),
                new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (installed || param.hasThrowable()) {
                    return;
                }
                installed = true;
                Context context = (Context) param.args[0];
                try {
                    HookDiagnostics.start(context);
                } catch (Throwable error) {
                    Log.w(TAG, "Diagnostics initialization failed", error);
                }
                DownloadMonitor.install(context.getClassLoader());
                try {
                    PackageInfo info = context.getPackageManager()
                            .getPackageInfo(context.getPackageName(), 0);
                    log("Telegram " + info.versionName + " (" + info.versionCode + ")");
                    SpeedPolicy.Config config = SpeedPolicy.loadConfig();
                    log("PROFILE " + config.name);
                    log("VIDEO_PROFILE " + (config.videoEnabled ? "on" : "off"));
                    if (!config.enabled && !config.videoEnabled) {
                        log("PROFILE_ORIGINAL: no download fields changed");
                        return;
                    }
                    HookResolver.Target target = HookResolver.resolve(context);
                    VideoPolicy video = config.videoEnabled ? VideoPolicy.install(context, target) : null;
                    if (!config.enabled && video == null) {
                        log("PROFILE_ORIGINAL: video unavailable; no download fields changed");
                        return;
                    }
                    HookDiagnostics.budgetRequired(target.updateBudget != null);
                    installScheduling(target, config, video);
                    log("HOOK_READY " + target.updateParams + "; budget=" + target.updateBudget);
                } catch (Throwable error) {
                    log("UNSUPPORTED: " + Log.getStackTraceString(error));
                }
            }
        });
    }

    private static void installScheduling(HookResolver.Target target, SpeedPolicy.Config config,
                                          VideoPolicy video) throws Throwable {
        SpeedPolicy policy = new SpeedPolicy(target);
        SpeedPolicy videoScheduling = new SpeedPolicy(target);
        List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
        try {
            hooks.add(XposedBridge.hookMethod(target.updateParams, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam update) {
                    if (!update.hasThrowable()) {
                        applyScheduling(update.thisObject, false, config, policy, videoScheduling, video);
                    }
                }
            }));
            if (target.updateBudget != null) {
                hooks.add(XposedBridge.hookMethod(target.updateBudget, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam update) {
                        if (!update.hasThrowable()) {
                            applyScheduling(update.thisObject, true, config, policy, videoScheduling, video);
                        }
                    }
                }));
            }
        } catch (Throwable error) {
            for (XC_MethodHook.Unhook hook : hooks) {
                hook.unhook();
            }
            if (video != null) {
                video.uninstall();
            }
            throw error;
        }
    }

    static void log(String message) {
        HookDiagnostics.record(message);
    }

    private static void applyScheduling(Object operation, boolean budget, SpeedPolicy.Config config,
                                        SpeedPolicy policy, SpeedPolicy videoScheduling, VideoPolicy video) {
        SpeedPolicy.Config selected = video == null ? config : video.choose(operation, config);
        boolean streaming = "video".equals(selected.name);
        SpeedPolicy active = streaming ? videoScheduling : policy;
        try {
            if (budget) {
                active.applyBudget(operation, selected);
            } else {
                active.apply(operation, selected);
            }
        } catch (Throwable error) {
            if (streaming) {
                video.disable();
            } else {
                policy.disable(error);
            }
        }
    }
}
