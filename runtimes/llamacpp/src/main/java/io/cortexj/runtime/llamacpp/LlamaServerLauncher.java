package io.cortexj.runtime.llamacpp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

interface LlamaServerLauncher {

    LlamaServerEndpoint launch(Path artifactPath) throws IOException;

    /**
     * Launch with server arguments. The only supported argument is
     * {@code gpuLayers} (offload that many layers to the GPU backend,
     * Metal/CUDA where the binary supports it). Unknown arguments must
     * be rejected, never silently ignored.
     */
    default LlamaServerEndpoint launch(Path artifactPath, java.util.Map<String, Object> serverArgs)
            throws IOException {
        return launch(artifactPath);
    }

    static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    static void awaitHealthy(String baseUrl, long timeoutMillis)
            throws IOException, InterruptedException {
        awaitHealthy(baseUrl, timeoutMillis, null);
    }

    static void awaitHealthy(String baseUrl, long timeoutMillis, Process process)
            throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        IOException last = null;
        while (System.currentTimeMillis() < deadline) {
            if (process != null && !isProcessAlive(process)) {
                throw new IOException("llama-server process exited before becoming healthy"
                        + (last == null ? "" : ": " + last.getMessage()));
            }
            try {
                java.net.HttpURLConnection connection =
                        (java.net.HttpURLConnection) new java.net.URL(baseUrl + "/health")
                                .openConnection();
                try {
                    connection.setConnectTimeout(2_000);
                    connection.setReadTimeout(2_000);
                    if (connection.getResponseCode() == 200) {
                        return;
                    }
                    last = new IOException("health endpoint answered HTTP "
                            + connection.getResponseCode());
                } finally {
                    connection.disconnect();
                }
            } catch (IOException probeFailure) {
                last = probeFailure;
            }
            if (process != null && !isProcessAlive(process)) {
                throw new IOException("llama-server process exited before becoming healthy: "
                        + last.getMessage());
            }
            TimeUnit.MILLISECONDS.sleep(250);
        }
        throw new IOException("llama-server did not become healthy in " + timeoutMillis
                + " ms: " + last.getMessage());
    }

    static boolean isProcessAlive(Process process) {
        try {
            process.exitValue();
            return false;
        } catch (IllegalThreadStateException stillRunning) {
            return true;
        }
    }
}
