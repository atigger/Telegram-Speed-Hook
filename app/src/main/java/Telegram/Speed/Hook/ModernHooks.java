package Telegram.Speed.Hook;

import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

final class ModernHooks {
    private static XposedInterface module;

    static void initialize(XposedInterface instance) {
        module = instance;
    }

    static int apiVersion() {
        return module.getApiVersion();
    }

    static android.content.SharedPreferences preferences(String group) {
        return module.getRemotePreferences(group);
    }

    static Handle hookMethod(Method method, HookCallback callback) {
        XposedInterface.HookHandle handle = module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    HookCallback.Invocation invocation = new HookCallback.Invocation(
                            chain.getThisObject(), chain.getArgs().toArray());
                    Object[] originalArgs = invocation.args.clone();
                    try {
                        callback.before(invocation);
                    } catch (Throwable error) {
                        invocation.args = originalArgs;
                        report(error);
                    }
                    try {
                        invocation.result = chain.proceed(invocation.args);
                    } catch (Throwable error) {
                        invocation.throwable = error;
                    }
                    try {
                        callback.after(invocation);
                    } catch (Throwable error) {
                        report(error);
                    }
                    if (invocation.throwable != null) {
                        throw invocation.throwable;
                    }
                    return invocation.result;
                });
        return handle::unhook;
    }

    static List<Handle> hookAllMethods(Class<?> type, String name, HookCallback callback) {
        List<Handle> handles = new ArrayList<>();
        try {
            for (Method method : type.getDeclaredMethods()) {
                if (name.equals(method.getName())) {
                    handles.add(hookMethod(method, callback));
                }
            }
            if (handles.isEmpty()) {
                throw new IllegalArgumentException("No hook target: " + type.getName() + "." + name);
            }
            return handles;
        } catch (Throwable error) {
            for (Handle handle : handles) {
                handle.unhook();
            }
            throw error;
        }
    }

    private static void report(Throwable error) {
        module.log(Log.ERROR, "TgSpeedHook", "Hook callback failed", error);
    }

    interface Handle {
        void unhook();
    }
}
