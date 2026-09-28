package io.cortexj.server.test;

import io.cortexj.server.CortexJServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CortexJServerBodyLimitTest {

    private static void post(URL url, String body, boolean chunked) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(5_000);
            if (chunked) {
                connection.setChunkedStreamingMode(1024);
            }
            OutputStream output = connection.getOutputStream();
            output.write(body.getBytes(StandardCharsets.UTF_8));
            output.close();
            assertEquals(413, connection.getResponseCode(),
                    "an oversized body must be rejected with 413");
        } finally {
            connection.disconnect();
        }
    }

    @Test
    void oversizedContentLengthIsRejectedEarly() throws Exception {
        System.setProperty("cortexj.server.maxBodyBytes", "1024");
        CortexJServer server = new CortexJServer(new CortexJServerBindAuthTest.EchoPlatform(),
                "server/test-model");
        try {
            int port = server.start(0);
            String base = "http://127.0.0.1:" + port;
            // body above the cap: Content-Length declares the size up front -> early reject
            StringBuilder big = new StringBuilder("{\"messages\":[{\"role\":\"user\",\"content\":\"");
            for (int i = 0; i < 2048; i++) {
                big.append('x');
            }
            big.append("\"}]}");
            post(new URL(base + "/v1/chat/completions"), big.toString(), false);
            // the server must keep serving other requests afterwards
            assertEquals(200, get(new URL(base + "/v1/models")),
                    "the server must keep serving after a 413");
        } finally {
            server.stop();
            System.clearProperty("cortexj.server.maxBodyBytes");
        }
    }

    @Test
    void chunkedOversizedBodyIsRejectedMidStream() throws Exception {
        System.setProperty("cortexj.server.maxBodyBytes", "4096");
        CortexJServer server = new CortexJServer(new CortexJServerBindAuthTest.EchoPlatform(),
                "server/test-model");
        try {
            int port = server.start(0);
            StringBuilder big = new StringBuilder("{\"messages\":[{\"role\":\"user\",\"content\":\"");
            for (int i = 0; i < 4096; i++) {
                big.append('x');
            }
            big.append("\"}]}");
            post(new URL("http://127.0.0.1:" + port + "/v1/chat/completions"),
                    big.toString(), true);
            assertEquals(200, get(new URL("http://127.0.0.1:" + port + "/v1/models")),
                    "the server must keep serving after a chunked 413");
        } finally {
            server.stop();
            System.clearProperty("cortexj.server.maxBodyBytes");
        }
    }

    private static int get(URL url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(5_000);
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }
}
