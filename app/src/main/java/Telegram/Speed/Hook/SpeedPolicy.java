package Telegram.Speed.Hook;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XSharedPreferences;

final class SpeedPolicy {
    private static final long MAX_FILE_SIZE = 2097152000L;
    static final String PREFERENCES = "settings";
    static final String PROFILE_KEY = "profile";
    static final String VIDEO_KEY = "video_optimization";
    static final int PROFILE_ORIGINAL = 0;
    static final int PROFILE_BALANCED = 1;
    static final int PROFILE_FAST = 2;
    private final HookResolver.Target target;
    private volatile boolean disabled;
    private int paramsLogCount;
    private int budgetCallLogCount;
    private int budgetApplyLogCount;
    private final Map<Object, Boolean> videoParamsReported = new WeakHashMap<>();
    private final Map<Object, Boolean> videoBudgetsReported = new WeakHashMap<>();

    SpeedPolicy(HookResolver.Target target) {
        this.target = target;
    }

    static Config loadConfig() {
        try {
            XSharedPreferences preferences = new XSharedPreferences(
                    BuildConfig.APPLICATION_ID, PREFERENCES);
            if (!preferences.getFile().canRead()) {
                throw new IllegalStateException("settings unreadable; open module and save first");
            }
            preferences.reload();
            int profile = preferences.getInt(PROFILE_KEY, PROFILE_FAST);
            boolean video = preferences.getBoolean(VIDEO_KEY, false);
            if (profile == PROFILE_BALANCED) {
                return new Config(true, 1024 * 1024, 8, 4, "balanced", video);
            }
            if (profile == PROFILE_FAST) {
                return new Config(true, 1024 * 1024, 12, 4, "high-request", video);
            }
            return new Config(false, 0, 0, 0, "original", video);
        } catch (Throwable error) {
            MainHook.log("PROFILE_READ_FAILED: " + error);
        }
        return new Config(false, 0, 0, 0, "original", false);
    }

    synchronized void apply(Object operation, Config selected) throws IllegalAccessException {
        if (!selected.enabled || disabled || guarded(operation)) {
            return;
        }
        Field chunk = null;
        Field cdn = null;
        List<Field> requests = new ArrayList<>();
        for (Field field : target.parameters) {
            int value = field.getInt(operation);
            if (value == 131072 || value == 524288) {
                if (chunk != null) {
                    throw new IllegalStateException("ambiguous chunk field");
                }
                chunk = field;
            }
        }
        if (chunk == null) {
            throw new IllegalStateException("chunk field failed validation");
        }
        int originalChunk = chunk.getInt(operation);
        for (Field field : target.parameters) {
            if (field.equals(chunk)) {
                continue;
            }
            int value = field.getInt(operation);
            if (value == MAX_FILE_SIZE / originalChunk && cdn == null) {
                cdn = field;
            } else if (value == 4 || value == 8) {
                requests.add(field);
            } else {
                throw new IllegalStateException("parameter failed validation: " + field);
            }
        }
        if (cdn == null || requests.size() != 2) {
            throw new IllegalStateException("incomplete parameter mapping");
        }
        List<Integer> originalValues = new ArrayList<>();
        for (Field field : target.parameters) {
            originalValues.add(field.getInt(operation));
        }
        try {
            chunk.setInt(operation, selected.chunkSize);
            cdn.setInt(operation, (int) (MAX_FILE_SIZE / selected.chunkSize));
            for (Field field : requests) {
                field.setInt(operation, selected.requestCount);
            }
        } catch (IllegalAccessException | RuntimeException error) {
            for (int index = 0; index < target.parameters.size(); index++) {
                target.parameters.get(index).setInt(operation, originalValues.get(index));
            }
            throw error;
        }
        boolean firstVideo = "video".equals(selected.name)
                && videoParamsReported.put(operation, true) == null;
        if (paramsLogCount < 5 || firstVideo) {
            paramsLogCount++;
            MainHook.log("PARAMS_APPLIED mode=" + selected.name + " chunk=" + chunk.getInt(operation)
                    + " requests=" + requests.get(0).getInt(operation)
                    + "/" + requests.get(1).getInt(operation)
                    + " cdnParts=" + cdn.getInt(operation));
        }
    }

    private boolean guarded(Object operation) throws IllegalAccessException {
        for (Field guard : target.guards) {
            if (guard.getBoolean(operation)) {
                return true;
            }
        }
        return false;
    }

    synchronized void applyBudget(Object operation, Config selected) throws IllegalAccessException {
        if (!selected.enabled || disabled) {
            return;
        }
        boolean guarded = guarded(operation);
        boolean enabled = target.budgetEnabled.getBoolean(operation);
        int chunk = target.currentChunk.getInt(operation);
        int currentRequests = target.currentRequests.getInt(operation);
        int connectionCount = target.connectionCount.getInt(operation);
        if (budgetCallLogCount < 12) {
            budgetCallLogCount++;
            MainHook.log("BUDGET_ENTER enabled=" + enabled
                    + " guarded=" + guarded
                    + " chunk=" + chunk
                    + " connections=" + connectionCount
                    + " currentRequests=" + currentRequests);
        }
        if (guarded || !enabled) {
            return;
        }
        if (chunk != selected.chunkSize) {
            return;
        }
        if ((connectionCount != 2 && connectionCount != 8)
                || (currentRequests != 2 * 1024 * 1024 / chunk
                && currentRequests != 16 * 1024 * 1024 / chunk)) {
            throw new IllegalStateException("unexpected budget defaults: connections="
                    + connectionCount + " requests=" + currentRequests);
        }
        try {
            target.connectionCount.setInt(operation, selected.connectionCount);
            target.currentRequests.setInt(operation, selected.requestCount);
        } catch (IllegalAccessException | RuntimeException error) {
            target.connectionCount.setInt(operation, connectionCount);
            target.currentRequests.setInt(operation, currentRequests);
            throw error;
        }
        boolean firstVideo = "video".equals(selected.name)
                && videoBudgetsReported.put(operation, true) == null;
        if (budgetApplyLogCount < 12 || firstVideo) {
            budgetApplyLogCount++;
            MainHook.log("BUDGET_APPLIED profile=" + selected.name + " chunk=" + selected.chunkSize
                    + " connections=" + target.connectionCount.getInt(operation)
                    + " currentRequests=" + target.currentRequests.getInt(operation));
        }
    }

    void disable(Throwable error) {
        if (!disabled) {
            disabled = true;
            MainHook.log("POLICY_DISABLED: " + error);
        }
    }

    static final class Config {
        final boolean enabled;
        final int chunkSize;
        final int requestCount;
        final int connectionCount;
        final String name;
        final boolean videoEnabled;

        Config(boolean enabled, int chunkSize, int requestCount, int connectionCount, String name,
               boolean videoEnabled) {
            this.enabled = enabled;
            this.chunkSize = chunkSize;
            this.requestCount = requestCount;
            this.connectionCount = connectionCount;
            this.name = name;
            this.videoEnabled = videoEnabled;
        }
    }
}
