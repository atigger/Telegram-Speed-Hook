package Telegram.Speed.Hook;

import android.os.Handler;
import android.os.SystemClock;

import org.json.JSONObject;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

final class DownloadMonitor {
    private final Map<String, Request> requests = new HashMap<>();
    private final Handler worker;
    private int peakRequests;
    private int peakLanes;
    private long submitted;
    private long completed;
    private long succeeded;
    private long rpcErrors;
    private long unknownCompletions;
    private long cleared;
    private long failed;
    private boolean disabled;
    private final Runnable tick = this::sample;

    private DownloadMonitor(Handler worker) {
        this.worker = worker;
    }

    static void install(ClassLoader loader) {
        List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
        try {
            Handler worker = HookDiagnostics.worker();
            if (worker == null) {
                throw new IllegalStateException("diagnostics unavailable");
            }
            Class<?> manager = Class.forName("org.telegram.tgnet.ConnectionsManager", false, loader);
            Method send = manager.getDeclaredMethod("native_sendRequest", int.class, long.class,
                    int.class, int.class, int.class, boolean.class, int.class, int.class);
            Method complete = manager.getDeclaredMethod("onRequestComplete", int.class, int.class,
                    long.class, int.class, String.class, int.class, long.class, long.class, int.class);
            Method clear = manager.getDeclaredMethod("onRequestClear", int.class, int.class, boolean.class);
            Method written = manager.getDeclaredMethod("onRequestWriteToSocket", int.class, int.class);
            if (!Modifier.isNative(send.getModifiers()) || !Modifier.isStatic(send.getModifiers())) {
                throw new IllegalStateException("unexpected native submission method");
            }
            for (Method method : new Method[]{send, complete, clear, written}) {
                if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != void.class) {
                    throw new IllegalStateException("unexpected download callback signature");
                }
            }
            DownloadMonitor monitor = new DownloadMonitor(worker);
            hooks.add(XposedBridge.hookMethod(send, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (((int) param.args[4] & 65535) == 2 && (int) param.args[7] > 0) {
                        monitor.begin(param.args);
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable()) {
                        monitor.end((int) param.args[0], (int) param.args[6], "failed");
                    }
                }
            }));
            hooks.add(XposedBridge.hookMethod(complete, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    String outcome = (long) param.args[2] != 0 ? "succeeded"
                            : (int) param.args[3] != 0 || param.args[4] != null
                            ? "rpc_error" : "unknown";
                    monitor.end((int) param.args[0], (int) param.args[1], outcome);
                }
            }));
            hooks.add(XposedBridge.hookMethod(clear, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    monitor.end((int) param.args[0], (int) param.args[1], "cleared");
                }
            }));
            hooks.add(XposedBridge.hookMethod(written, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    monitor.written((int) param.args[0], (int) param.args[1]);
                }
            }));
            MainHook.log("MONITOR_READY native download submissions / complete / clear / socket-write");
            HookDiagnostics.registerMonitorReset(monitor::resetCapture);
            worker.post(monitor.tick);
        } catch (Throwable error) {
            for (XC_MethodHook.Unhook hook : hooks) {
                hook.unhook();
            }
            MainHook.log("MONITOR_UNAVAILABLE: " + error);
        }
    }

    private synchronized void begin(Object[] args) {
        if (disabled || !HookDiagnostics.recording()) {
            return;
        }
        boolean idle = requests.isEmpty();
        String account = String.valueOf(args[0]);
        String lane = account + ":" + args[3] + ":" + ((int) args[4] >>> 16);
        String key = account + ":" + args[6];
        if (requests.containsKey(key)) {
            disabled = true;
            requests.clear();
            MainHook.log("MONITOR_UNAVAILABLE: duplicate request token");
            return;
        }
        requests.put(key, new Request(lane));
        submitted++;
        peakRequests = Math.max(peakRequests, requests.size());
        if (idle) {
            worker.removeCallbacks(tick);
            worker.post(tick);
        }
    }

    private synchronized void written(int account, int token) {
        Request request = requests.get(account + ":" + token);
        if (request != null) {
            request.written = true;
            peakLanes = Math.max(peakLanes, writtenLanes().size());
        }
    }

    private synchronized void end(int account, int token, String reason) {
        if (requests.remove(account + ":" + token) == null) {
            return;
        }
        if ("succeeded".equals(reason) || "rpc_error".equals(reason) || "unknown".equals(reason)) {
            completed++;
            if ("succeeded".equals(reason)) {
                succeeded++;
            } else if ("rpc_error".equals(reason)) {
                rpcErrors++;
            } else {
                unknownCompletions++;
            }
        } else if ("cleared".equals(reason)) {
            cleared++;
        } else {
            failed++;
        }
        if (requests.isEmpty()) {
            worker.removeCallbacks(tick);
            worker.post(tick);
        }
    }

    private Set<String> writtenLanes() {
        Set<String> lanes = new HashSet<>();
        for (Request request : requests.values()) {
            if (request.written) {
                lanes.add(request.lane);
            }
        }
        return lanes;
    }

    private synchronized void reset() {
        requests.clear();
        peakRequests = 0;
        peakLanes = 0;
        submitted = 0;
        completed = 0;
        succeeded = 0;
        rpcErrors = 0;
        unknownCompletions = 0;
        cleared = 0;
        failed = 0;
    }

    private synchronized void resetCapture() {
        reset();
        worker.removeCallbacks(tick);
        if (!disabled) {
            worker.post(tick);
        }
    }

    private synchronized void sample() {
        if (!HookDiagnostics.recording()) {
            reset();
            if (!disabled) {
                worker.postDelayed(tick, 2000);
            }
            return;
        }
        try {
            int writtenRequests = 0;
            for (Request request : requests.values()) {
                if (request.written) {
                    writtenRequests++;
                }
            }
            JSONObject snapshot = new JSONObject()
                    .put("captureId", HookDiagnostics.captureId())
                    .put("time", System.currentTimeMillis())
                    .put("elapsed", SystemClock.elapsedRealtime())
                    .put("available", !disabled)
                    .put("requests", requests.size())
                    .put("writtenRequests", writtenRequests)
                    .put("lanes", writtenLanes().size())
                    .put("peakRequests", peakRequests)
                    .put("peakLanes", peakLanes)
                    .put("submitted", submitted)
                    .put("completed", completed)
                    .put("succeeded", succeeded)
                    .put("rpcErrors", rpcErrors)
                    .put("unknownCompletions", unknownCompletions)
                    .put("cleared", cleared)
                    .put("failed", failed);
            HookDiagnostics.metrics(snapshot, true);
        } catch (Throwable error) {
            MainHook.log("MONITOR_UNAVAILABLE: " + error);
            disabled = true;
        }
        if (!disabled) {
            worker.postDelayed(tick, requests.isEmpty() ? 10000 : 2000);
        }
    }

    private static final class Request {
        final String lane;
        boolean written;

        Request(String lane) {
            this.lane = lane;
        }
    }
}
