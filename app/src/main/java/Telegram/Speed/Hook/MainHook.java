package Telegram.Speed.Hook;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.ApplicationInfo;

import io.github.libxposed.api.XposedModule;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;


public final class MainHook extends XposedModule {
    private static final String TAG = "TgSpeedHook";
    private boolean installed;
    private String processName;
    static String modulePath;
    static ApplicationInfo moduleInfo;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        processName = param.getProcessName();
        moduleInfo = getModuleApplicationInfo();
        modulePath = moduleInfo.sourceDir;
        ModernHooks.initialize(this);
        log(android.util.Log.INFO, TAG, "Modern libxposed API " + getApiVersion());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!"org.telegram.messenger".equals(param.getPackageName())
                || !param.getPackageName().equals(processName) || !param.isFirstPackage()) {
            return;
        }
        log(Log.INFO, TAG, "PACKAGE_READY " + param.getPackageName());
        try {
            installApplicationHook();
        } catch (Throwable error) {
            log(android.util.Log.ERROR, TAG, "Application hook failed", error);
        }
    }

    private void installApplicationHook() throws NoSuchMethodException {
        ModernHooks.hookMethod(Application.class.getDeclaredMethod("attach", Context.class),
                new HookCallback() {
            @Override
            protected void after(Invocation param) {
                if (installed || param.hasThrowable()) {
                    return;
                }
                installed = true;
                Context context = (Context) param.args[0];
                MainHook.this.log(Log.INFO, TAG, "APPLICATION_ATTACHED " + context.getPackageName());
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
                    log("CONFIG_SOURCE remote-preferences; api=" + ModernHooks.apiVersion());
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
                    MainHook.this.log(Log.INFO, TAG,
                            "HOOK_READY profile=" + config.name + "; video=" + config.videoEnabled);
                } catch (Throwable error) {
                    MainHook.this.log(Log.ERROR, TAG, "Hook installation failed", error);
                    log("UNSUPPORTED: " + Log.getStackTraceString(error));
                }
            }
        });
    }

    private static void installScheduling(HookResolver.Target target, SpeedPolicy.Config config,
                                          VideoPolicy video) throws Throwable {
        SpeedPolicy policy = new SpeedPolicy(target);
        SpeedPolicy videoScheduling = new SpeedPolicy(target);
        List<ModernHooks.Handle> hooks = new ArrayList<>();
        try {
            hooks.add(ModernHooks.hookMethod(target.updateParams, new HookCallback() {
                @Override
                protected void after(Invocation update) {
                    if (!update.hasThrowable()) {
                        applyScheduling(update.thisObject, false, config, policy, videoScheduling, video);
                    }
                }
            }));
            if (target.updateBudget != null) {
                hooks.add(ModernHooks.hookMethod(target.updateBudget, new HookCallback() {
                    @Override
                    protected void after(Invocation update) {
                        if (!update.hasThrowable()) {
                            applyScheduling(update.thisObject, true, config, policy, videoScheduling, video);
                        }
                    }
                }));
            }
        } catch (Throwable error) {
            for (ModernHooks.Handle hook : hooks) {
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
