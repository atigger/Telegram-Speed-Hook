package Telegram.Speed.Hook;

import android.os.Process;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

final class LogFiles {
    private static long lastCleanup;

    static String base() {
        return "/data/user/" + Process.myUid() / 100000
                + "/org.telegram.messenger/files/tg_speed_hook";
    }

    static String cleanup() {
        long cutoff = System.currentTimeMillis() - DiagnosticStore.RETENTION;
        return "base=" + base() + "; if [ -d $base/logs ]; then "
                + "for log in $base/logs/log-*.jsonl; do [ -f $log ] || continue; "
                + "stamp=${log##*/log-}; stamp=${stamp%.jsonl}; "
                + "case $stamp in ''|*[!0-9]*) continue;; esac; "
                + "if [ $((stamp + 60000)) -le " + cutoff + " ]; then rm -f -- $log; fi; done; fi; ";
    }

    static void recording(boolean enabled) throws Exception {
        String base = base();
        String command;
        if (enabled) {
            command = "uid=$(stat -c %u " + base.substring(0, base.indexOf("/files/")) + ") || exit 1; "
                    + "mkdir -p " + base + "/logs && chown $uid:$uid " + base + " " + base + "/logs "
                    + "&& chmod 700 " + base + " " + base + "/logs && touch " + base + "/recording.enabled "
                    + "&& chown $uid:$uid " + base + "/recording.enabled && chmod 600 " + base + "/recording.enabled";
        } else {
            command = "rm -f -- " + base + "/recording.enabled " + base + "/status.json "
                    + base + "/status.json.bak " + base + "/status.json.new || exit 1; "
                    + "if [ -d " + base + "/logs ]; then rm -f -- " + base + "/logs/log-*.jsonl || exit 1; fi";
        }
        RootShell.Result result = RootShell.runShared(command);
        if (result.exitCode != 0) {
            throw new IOException("recording control failed (exit=" + result.exitCode
                    + "): " + result.output.trim());
        }
        if (recordingEnabled() != enabled) {
            throw new IOException("recording state did not match the requested value");
        }
    }

    static boolean recordingEnabled() throws Exception {
        String directory = base().substring(0, base().indexOf("/files/"));
        RootShell.Result result = RootShell.runShared("test -d " + directory + " || exit 1; if [ -f " + base()
                + "/recording.enabled ]; then printf '__ON__'; else printf '__OFF__'; fi");
        if (result.exitCode == 0 && "__ON__".equals(result.output.trim())) {
            return true;
        }
        if (result.exitCode == 0 && "__OFF__".equals(result.output.trim())) {
            return false;
        }
        throw new IOException("recording state unavailable (exit=" + result.exitCode
                + "): " + result.output.trim());
    }

    static String clear() throws Exception {
        String id = UUID.randomUUID().toString();
        String directory = base().substring(0, base().indexOf("/files/"));
        String command = "base=" + base() + "; uid=$(stat -c %u " + directory + ") || exit 1; "
                + "mkdir -p $base/logs && chown $uid:$uid $base $base/logs "
                + "&& chmod 700 $base $base/logs "
                + "&& printf '" + id + "' > $base/capture.id.new "
                + "&& chown $uid:$uid $base/capture.id.new && chmod 600 $base/capture.id.new "
                + "&& (for log in $base/logs/log-*.jsonl; do [ -f $log ] || continue; "
                + "stamp=${log##*/log-}; stamp=${stamp%.jsonl}; "
                + "case $stamp in ''|*[!0-9]*) continue;; esac; rm -f -- $log || exit 1; done) "
                + "&& rm -f -- $base/status.json $base/status.json.bak $base/status.json.new "
                + "&& mv -f $base/capture.id.new $base/capture.id";
        RootShell.Result result = RootShell.runShared(command);
        if (result.exitCode != 0) {
            throw new IOException("log clear failed (exit=" + result.exitCode
                    + "): " + result.output.trim());
        }
        return id;
    }

    static String poll(long since) throws Exception {
        long now = System.currentTimeMillis();
        boolean clean = lastCleanup == 0 || now < lastCleanup || now - lastCleanup >= 60000;
        StringBuilder segments = new StringBuilder();
        long first = Math.max(since, now - 60000) / 60000 * 60000;
        long current = now / 60000 * 60000;
        for (long minute = first; minute <= current; minute += 60000) {
            segments.append("if [ -f $base/logs/log-").append(minute)
                    .append(".jsonl ]; then cat $base/logs/log-").append(minute)
                    .append(".jsonl || exit 1; fi; ");
        }
        String command = (clean ? cleanup() : "base=" + base() + "; ")
                + "printf '__CAPTURE__'; cat $base/capture.id 2>/dev/null; printf '\n'; "
                + "printf '__ACK__'; cat $base/capture.ack 2>/dev/null; printf '\n'; "
                + "if [ ! -f $base/recording.enabled ]; then "
                + "printf '__OFF__\n'; exit 0; fi; "
                + "printf '__ON__\n'; "
                + "printf '__BOOT__'; cat /proc/sys/kernel/random/boot_id; "
                + "for pid in $(pidof org.telegram.messenger); do "
                + "printf '__PROC__'; cat /proc/$pid/stat 2>/dev/null; done; "
                + "if [ -f $base/status.json ]; then "
                + "printf '__STATE__'; head -c 32768 $base/status.json; printf '\n'; fi; "
                + segments;
        RootShell.Result result = RootShell.runShared(command);
        if (result.exitCode != 0) {
            throw new IOException("local log read failed (exit=" + result.exitCode
                    + "): " + result.output.trim());
        }
        if (clean) {
            lastCleanup = now;
        }
        return result.output;
    }

    static void export(OutputStream output) throws Exception {
        String command = cleanup()
                + "capture=$(cat $base/capture.id 2>/dev/null); "
                + "ack=$(cat $base/capture.ack 2>/dev/null); [ \"$capture\" = \"$ack\" ] || exit 2; "
                + "for log in $base/logs/log-*.jsonl; do "
                + "[ -f $log ] || continue; cat $log || exit 1; done";
        java.lang.Process process = RootShell.startShared(command);
        FutureTask<Long> copy = new FutureTask<>(() -> {
            long count = 0;
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[32768];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    count += read;
                }
                output.flush();
            }
            return count;
        });
        FutureTask<Integer> wait = new FutureTask<>(process::waitFor);
        Thread reader = new Thread(copy, "tg-speed-export");
        Thread waiter = new Thread(wait, "tg-speed-export-wait");
        reader.setDaemon(true);
        waiter.setDaemon(true);
        reader.start();
        waiter.start();
        try {
            int code = wait.get(5, TimeUnit.MINUTES);
            long bytes = copy.get(5, TimeUnit.SECONDS);
            if (code != 0 || bytes == 0) {
                throw new IOException("export failed or no records");
            }
        } finally {
            process.destroy();
            copy.cancel(true);
            wait.cancel(true);
        }
    }
}
