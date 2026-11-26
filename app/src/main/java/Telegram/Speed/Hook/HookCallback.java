package Telegram.Speed.Hook;

import java.util.HashMap;
import java.util.Map;

abstract class HookCallback {
    protected void before(Invocation invocation) throws Throwable {
    }

    protected void after(Invocation invocation) throws Throwable {
    }

    static final class Invocation {
        final Object thisObject;
        Object[] args;
        Object result;
        Throwable throwable;
        private Map<String, Object> extras;

        Invocation(Object receiver, Object[] arguments) {
            thisObject = receiver;
            args = arguments;
        }

        boolean hasThrowable() {
            return throwable != null;
        }

        Object getResult() {
            return result;
        }

        void setObjectExtra(String key, Object value) {
            if (extras == null) {
                extras = new HashMap<>();
            }
            extras.put(key, value);
        }

        Object getObjectExtra(String key) {
            return extras == null ? null : extras.get(key);
        }
    }
}
