package Telegram.Speed.Hook;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.FieldUsingType;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

final class VideoPolicy {
    private static final SpeedPolicy.Config VIDEO = new SpeedPolicy.Config(
            true, 128 * 1024, 16, 4, "video", false);
    private static final String READ_START = "tg-speed-video-read";
    private static final String REQUEST_STREAM = "tg-speed-video-request-stream";
    private static final String PREVIOUS_STREAM = "tg-speed-video-previous-stream";
    private static final String REQUEST_OFFSET = "tg-speed-video-request-offset";
    private static final String REQUEST_LIMIT = "tg-speed-video-request-limit";
    private static final String REQUEST_TARGET = "tg-speed-video-request-target";
    private static final AtomicLong sequence = new AtomicLong();
    private final HookResolver.Target target;
    private final Field listener;
    private final Map<Object, Stream> streams = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Object, Stream> operations = Collections.synchronizedMap(new WeakHashMap<>());
    private final ThreadLocal<Stream> opening = new ThreadLocal<>();
    private final ThreadLocal<Stream> sending = new ThreadLocal<>();
    private final List<XC_MethodHook.Unhook> installed = new ArrayList<>();
    private volatile boolean disabled;

    private VideoPolicy(HookResolver.Target target, Field listener) {
        this.target = target;
        this.listener = listener;
    }

    static VideoPolicy install(Context context, HookResolver.Target target) {
        List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
        try (DexKitBridge bridge = DexKitBridge.create(context.getClassLoader(), false)) {
            List<MethodData> opens = bridge.findMethod(FindMethod.create()
                    .searchPackages("org.telegram.messenger")
                    .matcher(MethodMatcher.create().paramCount(1).returnType("long")
                            .usingStrings("account", "rid", "mime", "reference")));
            if (opens.size() != 1 || target.currentChunk == null) {
                throw new IllegalStateException("video stream or active-chunk mapping unavailable");
            }
            Method open = opens.get(0).getMethodInstance(context.getClassLoader());
            Class<?> streamType = open.getDeclaringClass();
            Method read = uniqueMethod(streamType, int.class, byte[].class, int.class, int.class);
            Method close = streamType.getDeclaredMethod("close");
            List<MethodData> priorities = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create().declaredClass(streamType.getName())
                            .paramCount(0).returnType("int").usingNumbers(3)));
            if (Modifier.isStatic(open.getModifiers()) || close.getReturnType() != void.class
                    || Modifier.isStatic(close.getModifiers()) || priorities.size() != 1) {
                throw new IllegalStateException("video method signature changed");
            }
            Method priority = priorities.get(0).getMethodInstance(context.getClassLoader());
            if (Modifier.isStatic(priority.getModifiers())) {
                throw new IllegalStateException("video priority is static");
            }
            Field uri = uniqueField(open.getParameterTypes()[0], Uri.class);
            List<Field> listeners = new ArrayList<>();
            for (Class<?> contract : streamType.getInterfaces()) {
                Method[] callbacks = contract.getMethods();
                if (callbacks.length != 1 || callbacks[0].getParameterTypes().length != 0
                        || callbacks[0].getReturnType() != void.class) {
                    continue;
                }
                for (Field field : target.updateParams.getDeclaringClass().getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers()) && field.getType() == contract) {
                        field.setAccessible(true);
                        listeners.add(field);
                    }
                }
            }
            if (listeners.size() != 1) {
                throw new IllegalStateException("ambiguous stream-listener mapping");
            }
            VideoPolicy policy = new VideoPolicy(target, listeners.get(0));
            Method load = positionMethod(bridge, target, listeners.get(0), context.getClassLoader());
            hooks.add(XposedBridge.hookMethod(load, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Stream stream = policy.streams.get(param.args[9]);
                    if (policy.disabled || stream == null || stream.closed) {
                        return;
                    }
                    long offset = (long) param.args[10];
                    if (offset < 0) {
                        return;
                    }
                    param.setObjectExtra(REQUEST_STREAM, stream);
                    param.setObjectExtra(PREVIOUS_STREAM, policy.opening.get());
                    policy.opening.set(stream);
                    if (offset > 0) {
                        param.args[11] = true;
                    }
                    if (stream.requestedOffset != offset) {
                        stream.requestedOffset = offset;
                        if (HookDiagnostics.recording()) {
                            MainHook.log("VIDEO_POSITION_REQUESTED stream=" + stream.id
                                    + " offset=" + offset + " priority=" + param.args[11]);
                        }
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Stream stream = (Stream) param.getObjectExtra(REQUEST_STREAM);
                    if (stream == null) {
                        return;
                    }
                    Stream previous = (Stream) param.getObjectExtra(PREVIOUS_STREAM);
                    if (previous == null) {
                        policy.opening.remove();
                    } else {
                        policy.opening.set(previous);
                    }
                    if (!param.hasThrowable() && param.getResult() != null) {
                        policy.operations.put(param.getResult(), stream);
                    }
                }
            }));
            hooks.add(XposedBridge.hookMethod(open, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (policy.disabled) {
                            return;
                        }
                        Uri location = (Uri) uri.get(param.args[0]);
                        if (location != null && "tg".equals(location.getScheme())
                                && isVideoMime(location.getQueryParameter("mime"))) {
                            Stream stream = new Stream();
                            policy.streams.put(param.thisObject, stream);
                            MainHook.log("VIDEO_STREAM_OPEN stream=" + stream.id);
                        } else {
                            policy.streams.remove(param.thisObject);
                        }
                    } catch (Throwable error) {
                        policy.disable();
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable()) {
                        Stream stream = policy.streams.remove(param.thisObject);
                        if (stream != null) {
                            MainHook.log("VIDEO_OPEN_FAILED stream=" + stream.id);
                        }
                    }
                }
            }));
            hooks.add(XposedBridge.hookMethod(priority, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Stream stream = policy.streams.get(param.thisObject);
                    if (!policy.disabled && stream != null && !param.hasThrowable()) {
                        int original = (int) param.getResult();
                        if (!stream.priorityReported) {
                            stream.priorityReported = true;
                            MainHook.log("VIDEO_QUEUE_NATIVE stream=" + stream.id
                                    + " queuePriority=" + original + "; preserve native reprioritization");
                        }
                    }
                }
            }));
            hooks.add(XposedBridge.hookMethod(read, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (HookDiagnostics.recording() && policy.streams.containsKey(param.thisObject)) {
                        param.setObjectExtra(READ_START, SystemClock.elapsedRealtime());
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Stream stream = policy.streams.get(param.thisObject);
                    if (stream == null) {
                        return;
                    }
                    Object started = param.getObjectExtra(READ_START);
                    long elapsed = started instanceof Long
                            ? SystemClock.elapsedRealtime() - (long) started : -1;
                    if (param.hasThrowable()) {
                        if (HookDiagnostics.recording()) {
                            MainHook.log("VIDEO_READ_FAILED stream=" + stream.id);
                        }
                        return;
                    }
                    int bytes = (int) param.getResult();
                    boolean first = bytes > 0 && !stream.firstData;
                    if (bytes > 0) {
                        stream.firstData = true;
                    }
                    if (elapsed < 0 || !HookDiagnostics.recording()) {
                        return;
                    }
                    if (first) {
                        MainHook.log("VIDEO_FIRST_DATA stream=" + stream.id + " openToDataMs="
                                + (SystemClock.elapsedRealtime() - stream.openedAt)
                                + " readMs=" + elapsed + " requestedOffset=" + stream.requestedOffset);
                    }
                    if (elapsed >= 250 && (int) param.args[2] > 0) {
                        MainHook.log("VIDEO_SLOW_READ stream=" + stream.id + " readMs=" + elapsed
                                + " returnedBytes=" + bytes + " requestedOffset=" + stream.requestedOffset);
                    }
                }
            }));
            hooks.add(XposedBridge.hookMethod(close, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Stream stream = policy.streams.remove(param.thisObject);
                    if (stream != null) {
                        stream.closed = true;
                        MainHook.log("VIDEO_STREAM_CLOSE stream=" + stream.id);
                    }
                }
            }));
            policy.installRequestTrace(context.getClassLoader(), hooks);
            policy.installed.addAll(hooks);
            MainHook.log("VIDEO_READY stream=" + streamType.getName()
                    + " chunk=131072 requests=16 lanes=4 queuePriority=native positionPriority=native");
            return policy;
        } catch (Throwable error) {
            for (XC_MethodHook.Unhook hook : hooks) {
                hook.unhook();
            }
            MainHook.log("VIDEO_UNAVAILABLE: " + error.getClass().getSimpleName()
                    + "; stream fingerprint/signature validation failed");
            return null;
        }
    }

    private static Method positionMethod(DexKitBridge bridge, HookResolver.Target target, Field listener,
                                         ClassLoader loader) throws Exception {
        Class<?> operation = target.updateParams.getDeclaringClass();
        Method found = null;
        for (MethodData data : bridge.findMethod(FindMethod.create()
                .searchPackages("org.telegram.messenger")
                .matcher(MethodMatcher.create().paramCount(13).returnType(operation.getName())))) {
            Method method = data.getMethodInstance(loader);
            if (!positionSignature(method, operation, listener.getType())) {
                continue;
            }
            boolean writesListener = false;
            for (var usage : data.getUsingFields()) {
                if (usage.getUsingType() == FieldUsingType.Write
                        && usage.getField().getFieldInstance(loader).equals(listener)) {
                    writesListener = true;
                }
            }
            if (writesListener) {
                if (found != null) {
                    throw new IllegalStateException("ambiguous video position loader");
                }
                found = method;
            }
        }
        if (found == null) {
            throw new IllegalStateException("video position loader unavailable");
        }
        found.setAccessible(true);
        return found;
    }

    static boolean positionSignature(Method method, Class<?> operation, Class<?> listener) {
        Class<?>[] types = method.getParameterTypes();
        return !Modifier.isStatic(method.getModifiers()) && method.getReturnType() == operation
                && types.length == 13 && types[5] == Object.class && types[6] == String.class
                && types[7] == long.class && types[8] == int.class && types[9] == listener
                && types[10] == long.class && types[11] == boolean.class && types[12] == int.class;
    }

    private void installRequestTrace(ClassLoader loader, List<XC_MethodHook.Unhook> hooks) {
        List<XC_MethodHook.Unhook> trace = new ArrayList<>();
        try {
            Method schedule = uniqueMethod(target.updateParams.getDeclaringClass(), void.class, int.class);
            Class<?> manager = Class.forName("org.telegram.tgnet.ConnectionsManager", false, loader);
            Method send = null;
            for (Method method : manager.getDeclaredMethods()) {
                Class<?>[] types = method.getParameterTypes();
                if (method.getName().equals("sendRequestSync") && method.getReturnType() == int.class
                        && !Modifier.isStatic(method.getModifiers()) && types.length == 8
                        && types[4] == int.class && types[5] == int.class
                        && types[6] == int.class && types[7] == boolean.class) {
                    if (send != null) {
                        throw new IllegalStateException("ambiguous synchronous request boundary");
                    }
                    send = method;
                }
            }
            if (send == null) {
                throw new IllegalStateException("synchronous request boundary unavailable");
            }
            trace.add(XposedBridge.hookMethod(schedule, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Stream stream = operations.get(param.thisObject);
                    try {
                        Stream active = streams.get(listener.get(param.thisObject));
                        if (active != null) {
                            stream = active;
                        }
                    } catch (Throwable error) {
                        traceFailure();
                        return;
                    }
                    if (disabled || stream == null || stream.closed) {
                        return;
                    }
                    param.setObjectExtra(REQUEST_STREAM, stream);
                    param.setObjectExtra(PREVIOUS_STREAM, sending.get());
                    sending.set(stream);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.getObjectExtra(REQUEST_STREAM) != null) {
                        Stream previous = (Stream) param.getObjectExtra(PREVIOUS_STREAM);
                        if (previous == null) {
                            sending.remove();
                        } else {
                            sending.set(previous);
                        }
                    }
                }
            }));
            trace.add(XposedBridge.hookMethod(send, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Stream stream = sending.get();
                    if (stream == null || disabled || stream.closed || !HookDiagnostics.recording()) {
                        return;
                    }
                    try {
                        Object request = param.args[0];
                        String type = request.getClass().getSimpleName();
                        if (!type.equals("TL_upload_getFile") && !type.equals("TL_upload_getCdnFile")
                                && !type.equals("TL_upload_getWebFile")) {
                            return;
                        }
                        long offset = ((Number) request.getClass().getField("offset").get(request)).longValue();
                        int limit = request.getClass().getField("limit").getInt(request);
                        if (offset >= 0 && limit > 0) {
                            param.setObjectExtra(REQUEST_STREAM, stream);
                            param.setObjectExtra(REQUEST_OFFSET, offset);
                            param.setObjectExtra(REQUEST_LIMIT, limit);
                            param.setObjectExtra(REQUEST_TARGET, stream.requestedOffset);
                        }
                    } catch (Throwable error) {
                        traceFailure();
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Stream stream = (Stream) param.getObjectExtra(REQUEST_STREAM);
                    if (stream == null || stream.closed || !HookDiagnostics.recording()
                            || param.hasThrowable() || (int) param.getResult() <= 0) {
                        return;
                    }
                    long offset = (long) param.getObjectExtra(REQUEST_OFFSET);
                    int length = (int) param.getObjectExtra(REQUEST_LIMIT);
                    long requested = (long) param.getObjectExtra(REQUEST_TARGET);
                    MainHook.log("VIDEO_RANGE_SUBMITTED stream=" + stream.id
                            + " offset=" + offset + " length=" + length + " target=" + requested
                            + " coversTarget=" + covers(offset, length, requested));
                }
            }));
            hooks.addAll(trace);
            MainHook.log("VIDEO_TRACE_READY position requests and submitted byte ranges");
        } catch (Throwable error) {
            for (XC_MethodHook.Unhook hook : trace) {
                hook.unhook();
            }
            traceFailure();
        }
    }

    private boolean traceReported;

    private synchronized void traceFailure() {
        if (!traceReported) {
            traceReported = true;
            MainHook.log("VIDEO_TRACE_UNAVAILABLE; video position priority remains independent");
        }
    }

    static boolean covers(long offset, int length, long target) {
        return offset >= 0 && length > 0 && target >= offset && target - offset < length;
    }

    SpeedPolicy.Config choose(Object operation, SpeedPolicy.Config ordinary) {
        if (disabled) {
            return ordinary;
        }
        try {
            Object callback = listener.get(operation);
            Stream stream = callback == null ? opening.get() : streams.get(callback);
            if (stream == null) {
                return ordinary;
            }
            for (Field guard : target.guards) {
                if (guard.getBoolean(operation)) {
                    return ordinary;
                }
            }
            int chunk = target.currentChunk.getInt(operation);
            if (useSmallChunks(chunk)) {
                return VIDEO;
            }
            if (!stream.reuseReported) {
                stream.reuseReported = true;
                MainHook.log("VIDEO_EXISTING_CHUNK stream=" + stream.id
                        + " chunk=" + chunk + "; preserve active request alignment");
            }
        } catch (Throwable error) {
            disable();
        }
        return ordinary;
    }

    static boolean isVideoMime(String mime) {
        return mime != null && mime.toLowerCase(Locale.ROOT).startsWith("video/");
    }

    static boolean useSmallChunks(int activeChunk) {
        return activeChunk == 0 || activeChunk == 128 * 1024;
    }

    void uninstall() {
        disable();
        for (XC_MethodHook.Unhook hook : installed) {
            hook.unhook();
        }
        installed.clear();
    }

    void disable() {
        if (!disabled) {
            disabled = true;
            MainHook.log("VIDEO_DISABLED: runtime safety validation failed; ordinary profile retained");
        }
    }

    private static Method uniqueMethod(Class<?> owner, Class<?> result, Class<?>... parameters)
            throws Exception {
        Method found = null;
        for (Method method : owner.getDeclaredMethods()) {
            if (method.getReturnType() == result && !Modifier.isStatic(method.getModifiers())
                    && java.util.Arrays.equals(method.getParameterTypes(), parameters)) {
                if (found != null) {
                    throw new IllegalStateException("ambiguous stream method");
                }
                found = method;
            }
        }
        if (found == null) {
            throw new NoSuchMethodException("stream signature unavailable");
        }
        return found;
    }

    private static Field uniqueField(Class<?> owner, Class<?> type) throws Exception {
        Field found = null;
        for (Field field : owner.getDeclaredFields()) {
            if (field.getType() == type && !Modifier.isStatic(field.getModifiers())) {
                if (found != null) {
                    throw new IllegalStateException("ambiguous stream URI");
                }
                field.setAccessible(true);
                found = field;
            }
        }
        if (found == null) {
            throw new NoSuchFieldException("stream URI unavailable");
        }
        return found;
    }

    private static final class Stream {
        final String id = Long.toHexString(SystemClock.elapsedRealtime()) + "-" + sequence.incrementAndGet();
        final long openedAt = SystemClock.elapsedRealtime();
        volatile boolean firstData;
        volatile boolean priorityReported;
        volatile boolean reuseReported;
        volatile boolean closed;
        volatile long requestedOffset = -1;
    }
}
