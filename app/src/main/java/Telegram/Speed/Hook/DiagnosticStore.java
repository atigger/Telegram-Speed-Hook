package Telegram.Speed.Hook;

import android.util.AtomicFile;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

final class DiagnosticStore {
    static final long RETENTION = 24L * 60 * 60 * 1000;
    private final File directory;
    private final File enabled;
    private final File capture;
    private final File acknowledged;
    private long lastCleanup;

    DiagnosticStore(File directory) {
        this.directory = new File(directory, "logs");
        enabled = new File(directory, "recording.enabled");
        capture = new File(directory, "capture.id");
        acknowledged = new File(directory, "capture.ack");
    }

    String captureId() throws Exception {
        if (!capture.isFile()) {
            return "";
        }
        try (FileInputStream input = new FileInputStream(capture)) {
            byte[] bytes = new byte[128];
            int count = input.read(bytes);
            String value = count < 0 ? "" : new String(bytes, 0, count, StandardCharsets.UTF_8).trim();
            if (!value.matches("[a-f0-9-]{36}") || input.read() != -1) {
                throw new IllegalStateException("invalid capture marker");
            }
            return value;
        }
    }

    boolean acknowledged(String id) throws Exception {
        if (id.isEmpty()) {
            return true;
        }
        if (!acknowledged.isFile()) {
            return false;
        }
        try (FileInputStream input = new FileInputStream(acknowledged)) {
            byte[] bytes = new byte[128];
            int count = input.read(bytes);
            return count == id.length() && input.read() == -1
                    && id.equals(new String(bytes, 0, count, StandardCharsets.UTF_8));
        }
    }

    void clear() throws Exception {
        File[] files = directory.listFiles();
        if (files == null && directory.isDirectory()) {
            throw new IllegalStateException("log directory unreadable");
        }
        if (files != null) {
            for (File segment : files) {
                if (segment.getName().matches("log-[0-9]+\\.jsonl") && !segment.delete()) {
                    throw new IllegalStateException("log deletion failed");
                }
            }
        }
        lastCleanup = 0;
    }

    void acknowledge(String id) throws Exception {
        AtomicFile marker = new AtomicFile(acknowledged);
        FileOutputStream output = null;
        try {
            output = marker.startWrite();
            output.write(id.getBytes(StandardCharsets.UTF_8));
            marker.finishWrite(output);
        } catch (Exception error) {
            if (output != null) {
                marker.failWrite(output);
            }
            throw error;
        }
    }

    boolean recording() {
        return enabled.isFile();
    }

    void append(JSONObject entry) throws Exception {
        cleanup();
        if (!recording()) {
            return;
        }
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("log directory unavailable");
        }
        long minute = System.currentTimeMillis() / 60000 * 60000;
        File segment = new File(directory, "log-" + minute + ".jsonl");
        try (FileOutputStream output = new FileOutputStream(segment, true)) {
            output.write((entry.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    void cleanup() {
        long now = System.currentTimeMillis();
        boolean keep = recording();
        if (keep && now >= lastCleanup && now - lastCleanup < 60000) {
            return;
        }
        lastCleanup = now;
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File segment : files) {
            String name = segment.getName();
            if (!name.matches("log-[0-9]+\\.jsonl")) {
                continue;
            }
            long minute;
            try {
                minute = Long.parseLong(name.substring(4, name.length() - 6));
            } catch (NumberFormatException error) {
                continue;
            }
            if (!keep || minute + 60000 <= now - RETENTION) {
                segment.delete();
            }
        }
    }
}
