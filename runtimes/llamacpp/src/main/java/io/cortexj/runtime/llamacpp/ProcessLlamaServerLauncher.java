package io.cortexj.runtime.llamacpp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

final class ProcessLlamaServerLauncher implements LlamaServerLauncher {

    interface ProcessStarter {

        Process start(ProcessBuilder builder) throws IOException;
    }

    private final String serverBinary;
    private final long startupTimeoutMillis;
    private final io.cortexj.observability.PlatformEventListener eventListener;
    private final ProcessStarter processStarter;

    ProcessLlamaServerLauncher(String serverBinary,
                                long startupTimeoutMillis,
                                io.cortexj.observability.PlatformEventListener eventListener) {
        this(serverBinary, startupTimeoutMillis, eventListener, new ProcessStarter() {
            @Override
            public Process start(ProcessBuilder builder) throws IOException {
                return builder.start();
            }
        });
    }

    ProcessLlamaServerLauncher(String serverBinary,
                                long startupTimeoutMillis,
                                io.cortexj.observability.PlatformEventListener eventListener,
                                ProcessStarter processStarter) {
        this.serverBinary = serverBinary;
        this.startupTimeoutMillis = startupTimeoutMillis;
        this.eventListener = eventListener == null
                ? io.cortexj.observability.PlatformEventListener.NOOP : eventListener;
        this.processStarter = processStarter;
    }

    @Override
    public LlamaServerEndpoint launch(Path artifactPath) throws IOException {
        if (artifactPath == null || !Files.isRegularFile(artifactPath)) {
            throw new IOException("GGUF artifact does not exist: " + artifactPath);
        }
        int port = LlamaServerLauncher.findFreePort();
        ProcessBuilder builder = new ProcessBuilder(
                serverBinary,
                "-m", artifactPath.toAbsolutePath().toString(),
                "--host", "127.0.0.1",
                "--port", String.valueOf(port));
        builder.redirectErrorStream(true);
        final Process process = processStarter.start(builder);

        Thread drain = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    java.io.InputStream output = process.getInputStream();
                    byte[] buffer = new byte[4096];
                    while (output.read(buffer) >= 0) {
                        // drain llama-server logs to avoid pipe backpressure
                    }
                } catch (IOException ignored) {
                    // process exited
                }
            }
        }, "llama-server-log-drain");
        drain.setDaemon(true);
        drain.start();

        final String baseUrl = "http://127.0.0.1:" + port;
        try {
            LlamaServerLauncher.awaitHealthy(baseUrl, startupTimeoutMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            killProcess(process);
            throw new IOException("interrupted while waiting for llama-server to start");
        } catch (IOException unhealthy) {
            killProcess(process);
            if (!isAlive(process)) {
                throw new IOException("llama-server exited during startup (exit code "
                        + exitCode(process) + "); check the model file and llama.cpp build: "
                        + unhealthy.getMessage());
            }
            throw unhealthy;
        }

        if (eventListener != io.cortexj.observability.PlatformEventListener.NOOP) {
            eventListener.onEvent(new io.cortexj.observability.PlatformEvent(
                    io.cortexj.observability.PlatformEvent.Type.RUNTIME_OPENED,
                    io.cortexj.observability.PlatformEvent.Level.INFO,
                    "llama-server started on " + baseUrl + " for "
                            + artifactPath.getFileName(),
                    java.util.Collections.<String, String>emptyMap(),
                    System.currentTimeMillis()));
        }

        return new LlamaServerEndpoint() {
            @Override
            public String baseUrl() {
                return baseUrl;
            }

            @Override
            public void stop() {
                killProcess(process);
            }
        };
    }

    private static void killProcess(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static boolean isAlive(Process process) {
        try {
            process.exitValue();
            return false;
        } catch (IllegalThreadStateException stillRunning) {
            return true;
        }
    }

    private static String exitCode(Process process) {
        try {
            return String.valueOf(process.exitValue());
        } catch (IllegalThreadStateException stillRunning) {
            return "unknown";
        }
    }
}
