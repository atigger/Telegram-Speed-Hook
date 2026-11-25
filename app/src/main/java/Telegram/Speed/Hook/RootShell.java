package Telegram.Speed.Hook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class RootShell {
    static Result run(String command) throws IOException, InterruptedException, TimeoutException {
        Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        return collect(process);
    }

    static Process startShared(String command) throws IOException {
        return sharedBuilder(command).start();
    }

    private static ProcessBuilder sharedBuilder(String command) {
        String quoted = "'" + command.replace("'", "'\\''") + "'";
        return new ProcessBuilder("su", "-c",
                "if [ -x /system/bin/nsenter ]; then exec /system/bin/nsenter -t 1 -m -- "
                        + "/system/bin/sh -c " + quoted + "; else exec /system/bin/sh -c "
                        + quoted + "; fi");
    }

    static Result runShared(String command) throws IOException, InterruptedException, TimeoutException {
        Process process = sharedBuilder(command).redirectErrorStream(true).start();
        return collect(process);
    }

    private static Result collect(Process process) throws IOException, InterruptedException, TimeoutException {
        FutureTask<String> read = new FutureTask<>(() -> {
            try (InputStream input = process.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                boolean overflow = false;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 131072) {
                        overflow = true;
                    } else if (!overflow) {
                        output.write(buffer, 0, count);
                    }
                }
                if (overflow) {
                    throw new IOException("root output limit exceeded");
                }
                return new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
        });
        FutureTask<Integer> wait = new FutureTask<>(process::waitFor);
        Thread reader = new Thread(read, "tg-speed-root-output");
        Thread waiter = new Thread(wait, "tg-speed-root-wait");
        reader.setDaemon(true);
        waiter.setDaemon(true);
        reader.start();
        waiter.start();
        try {
            int code = wait.get(30, TimeUnit.SECONDS);
            return new Result(code, read.get(2, TimeUnit.SECONDS));
        } catch (ExecutionException error) {
            throw new IOException("root command failed", error.getCause());
        } finally {
            process.destroy();
            read.cancel(true);
            wait.cancel(true);
        }
    }

    static final class Result {
        final int exitCode;
        final String output;

        Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}
