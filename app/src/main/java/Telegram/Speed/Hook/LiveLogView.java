package Telegram.Speed.Hook;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Editable;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.OutputStream;
import java.text.DateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

final class LiveLogView extends LinearLayout {
    private static final int EXPORT = 321;
    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Switch recording;
    private final LinearLayout details;
    private final TextView recordingStatus;
    private final Switch follow;
    private final Button watch;
    private final Button export;
    private final Button clear;
    private final TextView console;
    private final ScrollView viewport;
    private final Editable richText;
    private final Set<String> seen = new HashSet<>();
    private final ArrayDeque<Row> rows = new ArrayDeque<>();
    private final Runnable pollTask = this::poll;
    private boolean foreground;
    private boolean watching;
    private boolean busy;
    private boolean reading;
    private boolean stateKnown;
    private boolean restoring;
    private int generation;
    private long since = System.currentTimeMillis() - 60000;
    private String previousState = "";
    private String captureId = "";

    LiveLogView(Activity activity) {
        super(activity);
        this.activity = activity;
        setOrientation(VERTICAL);
        TextView heading = new TextView(activity);
        heading.setText(R.string.live_log_title);
        heading.setTextSize(20);
        addView(heading);
        recording = new Switch(activity);
        recording.setText(R.string.live_log_recording);
        recording.setChecked(false);
        recording.setEnabled(false);
        addView(recording);
        recordingStatus = new TextView(activity);
        recordingStatus.setText(R.string.live_log_checking);
        recordingStatus.setOnClickListener(view -> refreshRecording());
        addView(recordingStatus);
        details = new LinearLayout(activity);
        details.setOrientation(VERTICAL);
        details.setVisibility(GONE);
        addView(details);
        TextView help = new TextView(activity);
        help.setText(R.string.live_log_help);
        details.addView(help);
        watch = new Button(activity);
        watch.setText(R.string.live_log_watch);
        details.addView(watch);
        follow = new Switch(activity);
        follow.setText(R.string.live_log_follow);
        follow.setChecked(true);
        details.addView(follow);
        export = new Button(activity);
        export.setText(R.string.live_log_export);
        details.addView(export);
        clear = new Button(activity);
        clear.setText(R.string.live_log_clear);
        clear.setEnabled(false);
        details.addView(clear);
        clear.setOnClickListener(view -> new AlertDialog.Builder(activity)
                .setTitle(R.string.live_log_clear_title)
                .setMessage(R.string.live_log_clear_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.live_log_clear_action, (dialog, which) -> clearLogs())
                .show());
        console = new TextView(activity);
        console.setTextSize(13);
        console.setTypeface(Typeface.MONOSPACE);
        console.setTextColor(Color.rgb(220, 225, 232));
        console.setBackgroundColor(Color.rgb(20, 24, 32));
        int padding = (int) (12 * getResources().getDisplayMetrics().density);
        console.setPadding(padding, padding, padding, padding);
        console.setTextIsSelectable(true);
        console.setText("", TextView.BufferType.EDITABLE);
        richText = console.getEditableText();
        viewport = new ScrollView(activity);
        viewport.setFillViewport(true);
        viewport.addView(console);
        details.addView(viewport, new LayoutParams(LayoutParams.MATCH_PARENT,
                (int) (360 * getResources().getDisplayMetrics().density)));
        viewport.setOnTouchListener((view, event) -> {
            view.getParent().requestDisallowInterceptTouchEvent(true);
            return false;
        });
        follow.setOnCheckedChangeListener((button, checked) -> {
            if (checked) {
                viewport.post(() -> viewport.fullScroll(View.FOCUS_DOWN));
            }
        });
        append("ui-start", System.currentTimeMillis(), activity.getString(R.string.live_log_idle), "wait");
        watch.setOnClickListener(view -> {
            if (watching) {
                stop();
            } else {
                watching = true;
                watch.setText(R.string.live_log_pause);
                if (stateKnown) {
                    poll();
                } else {
                    refreshRecording();
                }
            }
        });
        recording.setOnCheckedChangeListener((button, checked) -> {
            if (!restoring) {
                changeRecording(checked);
            }
        });
        export.setOnClickListener(view -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TITLE, "Telegram-Speed-Hook-"
                    + System.currentTimeMillis() + ".jsonl");
            activity.startActivityForResult(intent, EXPORT);
        });
    }

    void setForeground(boolean value) {
        foreground = value;
        handler.removeCallbacks(pollTask);
        if (value && !busy) {
            refreshRecording();
        }
    }

    void stop() {
        watching = false;
        handler.removeCallbacks(pollTask);
        watch.setText(R.string.live_log_watch);
    }

    private void controls(boolean enabled) {
        busy = !enabled;
        recording.setEnabled(enabled && stateKnown);
        clear.setEnabled(enabled && stateKnown);
        export.setEnabled(enabled);
        watch.setEnabled(enabled);
        recordingStatus.setVisibility(stateKnown ? GONE : VISIBLE);
        recordingStatus.setText(busy ? R.string.live_log_checking : R.string.live_log_status_failed);
        recordingStatus.setEnabled(!busy);
    }

    private void changeRecording(boolean enabled) {
        if (busy) {
            return;
        }
        generation++;
        controls(false);
        new Thread(() -> {
            boolean success;
            try {
                LogFiles.recording(enabled);
                success = true;
            } catch (Exception error) {
                android.util.Log.w("TgSpeedHook", "Recording control failed", error);
                success = false;
            }
            boolean saved = success;
            activity.runOnUiThread(() -> {
                if (activity.isDestroyed()) {
                    return;
                }
                controls(true);
                if (!saved) {
                    restoreRecording(!enabled);
                    append("control-" + System.nanoTime(), System.currentTimeMillis(),
                            activity.getString(R.string.live_log_control_failed), "error");
                    stateKnown = false;
                    refreshRecording();
                    return;
                } else {
                    restoreRecording(enabled);
                    if (!enabled) {
                        richText.clear();
                        rows.clear();
                        seen.clear();
                    }
                    append("control-" + System.nanoTime(), System.currentTimeMillis(),
                            activity.getString(enabled ? R.string.live_log_enabled
                                    : R.string.live_log_disabled), enabled ? "ok" : "wait");
                    previousState = "";
                    if (enabled) {
                        watching = true;
                        watch.setText(R.string.live_log_pause);
                    }
                }
                if (foreground && watching) {
                    poll();
                }
            });
        }, "tg-speed-recording").start();
    }

    private void restoreRecording(boolean enabled) {
        restoring = true;
        recording.setChecked(enabled);
        restoring = false;
        details.setVisibility(enabled ? VISIBLE : GONE);
        if (!enabled) {
            stop();
        }
    }

    private void clearLogs() {
        if (busy || !stateKnown) {
            return;
        }
        generation++;
        handler.removeCallbacks(pollTask);
        controls(false);
        new Thread(() -> {
            String clearedCapture = null;
            Boolean enabled = null;
            try {
                clearedCapture = LogFiles.clear();
                enabled = LogFiles.recordingEnabled();
            } catch (Exception error) {
                android.util.Log.w("TgSpeedHook", "Log clear failed", error);
            }
            String result = clearedCapture;
            Boolean currentRecording = enabled;
            activity.runOnUiThread(() -> {
                if (activity.isDestroyed()) {
                    return;
                }
                if (result != null) {
                    richText.clear();
                    rows.clear();
                    seen.clear();
                    previousState = "";
                    captureId = result;
                    since = System.currentTimeMillis();
                    append("clear-" + result, since,
                            activity.getString(R.string.live_log_cleared), "ok");
                } else {
                    append("clear-error-" + System.nanoTime(), System.currentTimeMillis(),
                            activity.getString(R.string.live_log_clear_failed), "error");
                }
                stateKnown = currentRecording != null;
                if (stateKnown) {
                    restoreRecording(currentRecording);
                    if (result != null && currentRecording) {
                        watching = true;
                        watch.setText(R.string.live_log_pause);
                    }
                }
                controls(true);
                if (!stateKnown) {
                    refreshRecording();
                } else if (foreground && watching) {
                    poll();
                }
            });
        }, "tg-speed-clear").start();
    }

    private void refreshRecording() {
        if (busy) {
            return;
        }
        generation++;
        controls(false);
        new Thread(() -> {
            Boolean enabled = null;
            try {
                enabled = LogFiles.recordingEnabled();
            } catch (Exception error) {
                android.util.Log.w("TgSpeedHook", "Recording state read failed", error);
            }
            Boolean result = enabled;
            activity.runOnUiThread(() -> {
                if (activity.isDestroyed()) {
                    return;
                }
                stateKnown = result != null;
                if (stateKnown) {
                    restoreRecording(result);
                } else {
                    details.setVisibility(GONE);
                    append("state-error-" + System.nanoTime(), System.currentTimeMillis(),
                            activity.getString(R.string.live_log_status_failed), "error");
                    stop();
                }
                controls(true);
                if (foreground && watching) {
                    poll();
                }
            });
        }, "tg-speed-recording-state").start();
    }

    private void poll() {
        if (!foreground || !watching || busy || reading) {
            return;
        }
        reading = true;
        int requestGeneration = generation;
        long requestSince = Math.max(since, System.currentTimeMillis() - 60000);
        new Thread(() -> {
            String output;
            boolean success;
            try {
                output = LogFiles.poll(requestSince);
                success = true;
            } catch (Exception error) {
                android.util.Log.w("TgSpeedHook", "Local log read failed", error);
                output = activity.getString(R.string.live_log_read_failed);
                success = false;
            }
            String result = output;
            boolean loaded = success;
            activity.runOnUiThread(() -> {
                if (activity.isDestroyed()) {
                    return;
                }
                reading = false;
                if (requestGeneration != generation || busy || !watching) {
                    if (foreground && watching && !busy) {
                        handler.removeCallbacks(pollTask);
                        handler.postDelayed(pollTask, 2000);
                    }
                    return;
                }
                if (loaded) {
                    consume(result);
                } else {
                    append("read-error-" + System.nanoTime(), System.currentTimeMillis(), result, "error");
                    stop();
                }
                if (foreground && watching && !busy) {
                    handler.removeCallbacks(pollTask);
                    handler.postDelayed(pollTask, 2000);
                }
            });
        }, "tg-speed-log-read").start();
    }

    private void consume(String output) {
        String boot = "";
        String processes = "";
        String acknowledged = "";
        for (String line : output.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("__CAPTURE__")) {
                String current = line.substring(11).trim();
                if (!current.equals(captureId)) {
                    richText.clear();
                    rows.clear();
                    seen.clear();
                    previousState = "";
                    captureId = current;
                }
                continue;
            }
            if (line.startsWith("__ACK__")) {
                acknowledged = line.substring(7).trim();
                continue;
            }
            if (line.startsWith("__BOOT__")) {
                boot = line.substring(8).trim();
                continue;
            }
            if (line.startsWith("__PROC__")) {
                processes += line.substring(8) + "\n";
                continue;
            }
            if ("__OFF__".equals(line)) {
                restoreRecording(false);
                append("recording-off", System.currentTimeMillis(),
                        activity.getString(R.string.live_log_disabled), "wait");
                continue;
            }
            if ("__ON__".equals(line)) {
                restoreRecording(true);
                if (!captureId.equals(acknowledged)) {
                    append("pending-" + captureId, System.currentTimeMillis(),
                            activity.getString(R.string.live_log_clear_pending), "wait");
                } else if (!captureId.isEmpty()) {
                    append("capture-" + captureId, System.currentTimeMillis(),
                            activity.getString(R.string.live_log_capture_ready), "ok");
                }
                continue;
            }
            try {
                if (line.startsWith("__STATE__")) {
                    JSONObject state = new JSONObject(line.substring(9));
                    if (!captureId.equals(acknowledged)
                            || !captureId.equals(state.optString("captureId"))) {
                        continue;
                    }
                    boolean verified = !boot.isEmpty() && boot.equals(state.optString("bootId"));
                    boolean matched = false;
                    for (String process : processes.split("\n")) {
                        if (process.startsWith(state.optInt("pid") + " (")
                                && !state.optString("processStart").isEmpty()
                                && state.optString("processStart").equals(HookDiagnostics.processStart(process))) {
                            matched = true;
                        }
                    }
                    verified &= matched;
                    String key = state.optLong("startedAt") + ":" + state.optString("phase")
                            + ":" + state.optBoolean("monitorAvailable") + ":" + verified
                            + ":" + state.optString("videoState");
                    if (!key.equals(previousState)) {
                        previousState = key;
                        String phase = state.optString("phase");
                        append("session-" + key, System.currentTimeMillis(),
                                activity.getString(verified ? R.string.live_log_current
                                        : R.string.live_log_history), verified ? "ok" : "error");
                        if (!state.optBoolean("monitorAvailable")) {
                            append("monitor-" + key, System.currentTimeMillis(),
                                    activity.getString(R.string.live_log_monitor_unavailable), "error");
                        }
                        append("state-" + key, System.currentTimeMillis(),
                                activity.getString(R.string.live_log_state,
                                        state.optString("moduleVersion"), state.optString("telegram"),
                                        phase, state.optString("profile")),
                                "disabled".equals(phase) || "unsupported".equals(phase)
                                        || "profile_error".equals(phase) ? "error" : "wait");
                        append("video-" + key, System.currentTimeMillis(),
                                activity.getString(R.string.live_log_video_state,
                                        state.optBoolean("videoEnabled")
                                                ? state.optString("videoState", "waiting") : "off"),
                                "unavailable".equals(state.optString("videoState")) ? "error" : "wait");
                    }
                    continue;
                }
                JSONObject entry = new JSONObject(line);
                if (!captureId.equals(acknowledged)
                        || !captureId.equals(entry.optString("captureId"))) {
                    continue;
                }
                long time = entry.optLong("time");
                if (time < System.currentTimeMillis() - DiagnosticStore.RETENTION) {
                    continue;
                }
                since = Math.max(since, time);
                if ("sample".equals(entry.optString("type"))) {
                    append(entry.optString("id"), time,
                            activity.getString(entry.has("succeeded") ? R.string.live_log_sample
                                            : R.string.live_log_sample_legacy,
                                    entry.optInt("requests"), entry.optInt("writtenRequests"),
                                    entry.optInt("lanes"), entry.optInt("peakRequests"),
                                    entry.optInt("peakLanes"), entry.optLong("completed"),
                                    entry.optLong("cleared"), entry.optLong("succeeded"),
                                    entry.optLong("rpcErrors"), entry.optLong("failed"),
                                    entry.optLong("unknownCompletions")), "sample");
                } else {
                    String message = entry.optString("message");
                    append(entry.optString("id"), time, message,
                            message.contains("FAILED") || message.contains("UNSUPPORTED")
                                    || message.contains("DISABLED") || message.contains("UNAVAILABLE")
                                    ? "error" : message.contains("APPLIED") || message.contains("READY")
                                    ? "ok" : "wait");
                }
            } catch (Exception error) {
                append("invalid-" + line.hashCode(), System.currentTimeMillis(),
                        activity.getString(R.string.live_log_invalid), "error");
            }
        }
    }

    private void append(String id, long time, String message, String level) {
        if (!seen.add(id)) {
            return;
        }
        String text = "[" + DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date(time))
                + "] " + message + "\n";
        int color = "error".equals(level) ? Color.rgb(255, 120, 120)
                : "ok".equals(level) ? Color.rgb(100, 220, 160)
                : "sample".equals(level) ? Color.rgb(110, 195, 255) : Color.rgb(245, 205, 110);
        SpannableString line = new SpannableString(text);
        line.setSpan(new ForegroundColorSpan(color), 0, line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        line.setSpan(new StyleSpan(Typeface.BOLD), 0, text.indexOf(']') + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        richText.append(line);
        rows.add(new Row(id, time, text.length()));
        long cutoff = System.currentTimeMillis() - DiagnosticStore.RETENTION;
        while (!rows.isEmpty() && rows.getFirst().time < cutoff) {
            Row old = rows.removeFirst();
            richText.delete(0, old.length);
            seen.remove(old.id);
        }
        if (follow.isChecked()) {
            viewport.post(() -> viewport.fullScroll(View.FOCUS_DOWN));
        }
    }

    void onExportResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != EXPORT || resultCode != Activity.RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        stop();
        controls(false);
        new Thread(() -> {
            boolean success;
            try (OutputStream output = activity.getContentResolver().openOutputStream(uri, "wt")) {
                if (output == null) {
                    throw new IllegalStateException("export destination unavailable");
                }
                LogFiles.export(output);
                success = true;
            } catch (Exception error) {
                success = false;
            }
            boolean saved = success;
            activity.runOnUiThread(() -> {
                if (!activity.isDestroyed()) {
                    controls(true);
                    Toast.makeText(activity, saved ? R.string.live_log_exported
                            : R.string.live_log_export_failed, Toast.LENGTH_LONG).show();
                }
            });
        }, "tg-speed-export-save").start();
    }

    private static final class Row {
        final String id;
        final long time;
        final int length;

        Row(String id, long time, int length) {
            this.id = id;
            this.time = time;
            this.length = length;
        }
    }
}
