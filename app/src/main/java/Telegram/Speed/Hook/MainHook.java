package Telegram.Speed.Hook;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.util.Log;

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
    public void initZygote(StartupParam startup) {
        modulePath = startup.modulePath;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackage) {
        if (!"org.telegram.messenger".equals(loadPackage.packageName)
                || !loadPackage.packageName.equals(loadPackage.processName)) {
            return;
        }
        XposedBridge.hookAllMethods(Application.class, "attach", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (installed || param.hasThrowable()) {
                    return;
                }
                installed = true;
                Context context = (Context) param.args[0];
                try {
                    PackageInfo info = context.getPackageManager()
                            .getPackageInfo(context.getPackageName(), 0);
                    log("Telegram " + info.versionName + " (" + info.versionCode + ")");
                    SpeedPolicy.Config config = SpeedPolicy.loadConfig();
                    log("PROFILE " + config.name);
                    if (!config.enabled) {
                        log("PROFILE_ORIGINAL: no download fields changed");
                        return;
                    }
                    HookResolver.Target target = HookResolver.resolve(context);
                    SpeedPolicy policy = new SpeedPolicy(target, config);
                    XC_MethodHook.Unhook paramsHook = XposedBridge.hookMethod(
                            target.updateParams, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam update) {
                            if (!update.hasThrowable()) {
                                try {
                                    policy.apply(update.thisObject);
                                } catch (Throwable error) {
                                    policy.disable(error);
                                }
                            }
                        }
                    });
                    try {
                        if (target.updateBudget != null) {
                        XposedBridge.hookMethod(target.updateBudget, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam update) {
                                if (!update.hasThrowable()) {
                                    try {
                                        policy.applyBudget(update.thisObject);
                                    } catch (Throwable error) {
                                        policy.disable(error);
                                    }
                                }
                            }
                        });
                        }
                    } catch (Throwable error) {
                        paramsHook.unhook();
                        throw error;
                    }
                    log("HOOK_READY " + target.updateParams + "; budget=" + target.updateBudget);
                } catch (Throwable error) {
                    log("UNSUPPORTED: " + Log.getStackTraceString(error));
                }
            }
        });
    }

    static void log(String message) {
        Log.i(TAG, message);
        XposedBridge.log(TAG + ": " + message);
    }
}
