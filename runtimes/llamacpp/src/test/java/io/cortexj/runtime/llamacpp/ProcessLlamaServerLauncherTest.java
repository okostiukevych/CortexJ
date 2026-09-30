package io.cortexj.runtime.llamacpp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessLlamaServerLauncherTest {

    @TempDir
    Path tempDir;

    static class StubbornProcess extends Process {

        volatile boolean running = true;
        volatile boolean destroyCalled;
        volatile boolean destroyForciblyCalled;

        @Override
        public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override
                public void write(int b) {
                }
            };
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return !running;
        }

        @Override
        public int waitFor() throws InterruptedException {
            while (running) {
                Thread.sleep(5);
            }
            return 0;
        }

        @Override
        public int exitValue() {
            if (running) {
                throw new IllegalThreadStateException("still running");
            }
            return 0;
        }

        @Override
        public void destroy() {
            destroyCalled = true;
        }

        @Override
        public Process destroyForcibly() {
            destroyForciblyCalled = true;
            running = false;
            return this;
        }
    }

    private ProcessLlamaServerLauncher launcherFor(final StubbornProcess process, long timeoutMillis) {
        return new ProcessLlamaServerLauncher("fake-llama-server", timeoutMillis, null,
                new ProcessLlamaServerLauncher.ProcessStarter() {
                    @Override
                    public Process start(ProcessBuilder builder) {
                        return process;
                    }
                });
    }

    @Test
    void unhealthyStartupForceKillsSigtermIgnoringProcess() throws Exception {
        StubbornProcess process = new StubbornProcess();
        ProcessLlamaServerLauncher launcher = launcherFor(process, 200);
        Path artifact = Files.createTempFile(tempDir, "fake-model", ".gguf");
        try {
            assertThrows(IOException.class, new org.junit.jupiter.api.function.Executable() {
                @Override
                public void execute() throws IOException {
                    launcher.launch(artifact);
                }
            });
            assertTrue(process.destroyCalled, "SIGTERM must be attempted first");
            assertTrue(process.destroyForciblyCalled,
                    "a startup failure must escalate to destroyForcibly when SIGTERM is ignored");
            assertFalse(process.running, "the process must be dead before the exception propagates");
        } finally {
            Files.deleteIfExists(artifact);
        }
    }

    @Test
    void interruptedStartupForceKillsProcess() throws Exception {
        StubbornProcess process = new StubbornProcess();
        final ProcessLlamaServerLauncher launcher = launcherFor(process, 5_000);
        final Path artifact = Files.createTempFile(tempDir, "fake-model", ".gguf");
        Thread.currentThread().interrupt();
        try {
            assertThrows(IOException.class, new org.junit.jupiter.api.function.Executable() {
                @Override
                public void execute() throws IOException {
                    launcher.launch(artifact);
                }
            });
            assertTrue(process.destroyForciblyCalled,
                    "an interrupted startup must not leave a live process behind");
            assertFalse(process.running);
        } finally {
            Thread.interrupted();
            Files.deleteIfExists(artifact);
        }
    }

    @Test
    void healthyLaunchDoesNotKillAndStopRunsTheFullLadder() throws Exception {
        StubbornProcess process = new StubbornProcess() {
            @Override
            public boolean waitFor(long timeout, TimeUnit unit) {
                running = false;
                return true;
            }
        };
        com.sun.net.httpserver.HttpServer health = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        health.createContext("/health", new com.sun.net.httpserver.HttpHandler() {
            @Override
            public void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            }
        });
        health.start();
        try {
            final ProcessLlamaServerLauncher launcher = new ProcessLlamaServerLauncher(
                    "fake-llama-server", 5_000, null,
                    new ProcessLlamaServerLauncher.ProcessStarter() {
                        @Override
                        public Process start(ProcessBuilder builder) {
                            return process;
                        }
                    });
            Path artifact = Files.createTempFile(tempDir, "fake-model", ".gguf");
            try {
                LlamaServerEndpoint endpoint = launcher.launchWithHealthUrl(artifact,
                        "http://127.0.0.1:" + health.getAddress().getPort());
                assertFalse(process.destroyCalled,
                        "a healthy launch must not kill the process");
                endpoint.stop();
                assertTrue(process.destroyCalled, "stop() must run SIGTERM first");
                assertFalse(process.running,
                        "stop() must run the full destruction ladder");
            } finally {
                Files.deleteIfExists(artifact);
            }
        } finally {
            health.stop(0);
        }
    }
}
