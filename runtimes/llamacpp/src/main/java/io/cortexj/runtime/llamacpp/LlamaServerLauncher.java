package io.cortexj.runtime.llamacpp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

interface LlamaServerLauncher {

    LlamaServerEndpoint launch(Path artifactPath) throws IOException;

    static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    static void awaitHealthy(String baseUrl, long timeoutMillis)
            throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        IOException last = null;
        while (System.currentTimeMillis() < deadline) {
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
            TimeUnit.MILLISECONDS.sleep(250);
        }
        throw new IOException("llama-server did not become healthy in " + timeoutMillis
                + " ms" + (last == null ? "" : ": " + last.getMessage()));
    }
}
