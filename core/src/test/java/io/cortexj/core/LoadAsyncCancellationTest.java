package io.cortexj.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.cortexj.CortexJ;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelArtifact;
import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelReference;
import io.cortexj.spi.repository.ResolvedArtifact;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Tag;

@Tag("concurrency")
class LoadAsyncCancellationTest {

    private static final int PAYLOAD_SIZE = 200;

    private HttpServer server;
    private int port;

    @TempDir
    Path tempDir;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(2));
        port = server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    /** Serves the payload in 10-byte chunks with 50ms pauses: a slow download. */
    private void serveSlowly(final byte[] payload) {
        server.createContext("/models/model.bin", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                java.io.InputStream input = exchange.getRequestBody();
                byte[] buffer = new byte[4096];
                while (input.read(buffer) >= 0) {
                }
                input.close();
                exchange.getResponseHeaders().add("Connection", "close");
                exchange.sendResponseHeaders(200, payload.length);
                OutputStream out = exchange.getResponseBody();
                for (int offset = 0; offset < payload.length; offset += 10) {
                    int end = Math.min(offset + 10, payload.length);
                    out.write(java.util.Arrays.copyOfRange(payload, offset, end));
                    out.flush();
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                out.close();
            }
        });
    }

    @Test
    void loadTriggeredDownloadCanBeCancelledPromptlyAndPartialIsKept() throws Exception {
        byte[] payload = new byte[PAYLOAD_SIZE];
        java.util.Arrays.fill(payload, (byte) 'd');
        serveSlowly(payload);

        final ResolvedArtifact artifact = new ResolvedArtifact() {
            @Override
            public ModelArtifact artifact() {
                return new ModelArtifact.Abstract("model.bin", ArtifactFormat.GGUF,
                        PAYLOAD_SIZE, null, null) {
                };
            }

            @Override
            public URI location() {
                return URI.create("http://127.0.0.1:" + port + "/models/model.bin");
            }

            @Override
            public Map<String, String> downloadHeaders() {
                return Collections.singletonMap("Connection", "close");
            }
        };
        io.cortexj.spi.repository.ModelRepositoryProvider repository = new io.cortexj.spi.repository.ModelRepositoryProvider() {
            @Override
            public io.cortexj.spi.repository.RepositoryId id() {
                return io.cortexj.spi.repository.RepositoryId.of("async-repo");
            }

            @Override
            public boolean supports(ModelReference reference) {
                return true;
            }

            @Override
            public io.cortexj.spi.repository.ResolvedModel resolve(ModelReference reference,
                                                                  io.cortexj.spi.repository.ResolutionContext context) {
                io.cortexj.model.ModelDescriptor descriptor = io.cortexj.model.ModelDescriptor
                        .builder("test/async-model")
                        .revision("rev1")
                        .task(io.cortexj.model.TaskCapability.TEXT_GENERATION)
                        .artifact(artifact.artifact())
                        .build();
                return new io.cortexj.spi.repository.ResolvedModel(descriptor, "rev1",
                        id(), Collections.singletonList(artifact));
            }
        };

            DefaultCortexJ platform = (DefaultCortexJ) CortexJ.builder()
                    .runtimeProvider(new ModelHandleCloseRaceTest.CountingRuntimeProvider())
                    .repositoryProvider(repository)
                    .workingDirectory(tempDir.resolve("work"))
                    .build();
        try {
            Future<ModelHandle> future = platform.models().loadAsync(ModelReference.of("test/async-model"));

            // wait until the download is in-flight with partial progress
            Path partial = platform.cache().root().resolve("downloads-partial")
                    .resolve("async-repo~rev1~model.bin.part");
            long deadline = System.currentTimeMillis() + 30_000L;
            while (!Files.isRegularFile(partial) || Files.size(partial) == 0) {
                if (System.currentTimeMillis() > deadline) {
                    throw new AssertionError("the load never produced partial download progress");
                }
                Thread.sleep(10);
            }
            // let the copy settle into its blocked read, then cancel
            Thread.sleep(50);

            long cancelStarted = System.nanoTime();
            assertTrue(future.cancel(true), "cancel() on a running load must succeed");
            long cancelMillis = (System.nanoTime() - cancelStarted) / 1_000_000L;

            assertTrue(future.isCancelled());
            try {
                future.get(10, TimeUnit.SECONDS);
                throw new AssertionError("get() on a cancelled load must throw");
            } catch (CancellationException expected) {
            }

            // prompt: the copy stops and the loading task finishes quickly. The
            // chunk already read (past the cancel-flag check) when cancel() lands
            // may still be written - the copy settles within one 50ms chunk
            // interval, then the partial must stop growing entirely
            Thread.sleep(300);
            long settled = Files.size(partial);
            Thread.sleep(500);
            assertEquals(settled, Files.size(partial),
                    "the download must stop promptly after cancel() - no further progress beyond the in-flight chunk");
            assertTrue(cancelMillis < 5_000L,
                    "cancel() must return promptly (took " + cancelMillis + "ms)");
            assertTrue(Files.isRegularFile(partial),
                    "the resume partial must be kept per existing cancellation semantics");
        } finally {
            platform.close();
        }
    }
}
