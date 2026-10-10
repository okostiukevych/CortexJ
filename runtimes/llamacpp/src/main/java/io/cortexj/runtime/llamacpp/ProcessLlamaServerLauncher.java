package io.cortexj.runtime.llamacpp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
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
        return launchWithHealthUrl(artifactPath, null);
    }

    @Override
    public LlamaServerEndpoint launch(Path artifactPath, java.util.Map<String, Object> serverArgs)
            throws IOException {
        return launchWithHealthUrl(artifactPath, null, gpuLayers(serverArgs));
    }

    /**
     * The only supported server argument: {@code gpuLayers} offloads that
     * many layers to the GPU backend (Metal/CUDA where the binary
     * supports it). Absent means the binary default (CPU unless the
     * binary offloads by default). Anything else fails typed.
     */
    static long gpuLayers(java.util.Map<String, Object> serverArgs) throws IOException {
        if (serverArgs == null) {
            return -1;
        }
        Object value = serverArgs.get("gpuLayers");
        if (value == null) {
            return -1;
        }
        long layers;
        if (value instanceof Number) {
            layers = ((Number) value).longValue();
        } else {
            try {
                layers = Long.parseLong(String.valueOf(value).trim());
            } catch (NumberFormatException notANumber) {
                throw new IOException("llama.cpp option 'gpuLayers' must be a non-negative integer,"
                        + " got: " + value);
            }
        }
        if (layers < 0) {
            throw new IOException("llama.cpp option 'gpuLayers' must be a non-negative integer,"
                    + " got: " + value);
        }
        return layers;
    }

    LlamaServerEndpoint launchWithHealthUrl(Path artifactPath, String healthBaseUrl) throws IOException {
        return launchWithHealthUrl(artifactPath, healthBaseUrl, -1);
    }

    LlamaServerEndpoint launchWithHealthUrl(Path artifactPath, String healthBaseUrl, long gpuLayers)
            throws IOException {
        if (artifactPath == null || !Files.isRegularFile(artifactPath)) {
            throw new IOException("GGUF artifact does not exist: " + artifactPath);
        }
        int port = LlamaServerLauncher.findFreePort();
        final String effectiveHealthUrl = healthBaseUrl == null
                ? "http://127.0.0.1:" + port : healthBaseUrl;
        java.util.List<String> command = new java.util.ArrayList<String>();
        command.add(serverBinary);
        command.add("-m");
        command.add(artifactPath.toAbsolutePath().toString());
        command.add("--host");
        command.add("127.0.0.1");
        command.add("--port");
        command.add(String.valueOf(port));
        if (gpuLayers >= 0) {
            command.add("--n-gpu-layers");
            command.add(String.valueOf(gpuLayers));
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().clear();
        builder.environment().putAll(allowlistedEnvironment(System.getenv()));
        builder.directory(workingDirectory(artifactPath).toFile());
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
            LlamaServerLauncher.awaitHealthy(effectiveHealthUrl, startupTimeoutMillis);
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

    static Map<String, String> allowlistedEnvironment(Map<String, String> parentEnvironment) {
        Map<String, String> child = new LinkedHashMap<String, String>();
        String[] directNames = {"PATH", "HOME", "TMPDIR", "TEMP", "TMP", "LANG"};
        for (String name : directNames) {
            String value = parentEnvironment.get(name);
            if (value != null) {
                child.put(name, value);
            }
        }
        for (Map.Entry<String, String> entry : parentEnvironment.entrySet()) {
            String name = entry.getKey();
            if (name.startsWith("LC_") || name.startsWith("LLAMA_")) {
                child.put(name, entry.getValue());
            }
        }
        return child;
    }

    private static Path workingDirectory(Path artifactPath) {
        Path parent = artifactPath.toAbsolutePath().getParent();
        return parent != null ? parent : Paths.get(System.getProperty("java.io.tmpdir"));
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
