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

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void allowlistedEnvironmentStripsSecretsAndKeepsSafeVariables() {
        java.util.Map<String, String> parent = new java.util.LinkedHashMap<String, String>();
        parent.put("PATH", "/usr/bin:/bin");
        parent.put("HOME", "/home/tester");
        parent.put("TMPDIR", "/tmp");
        parent.put("TEMP", "/temp");
        parent.put("TMP", "/tmp");
        parent.put("LANG", "en_US.UTF-8");
        parent.put("LC_ALL", "en_US.UTF-8");
        parent.put("LC_CTYPE", "UTF-8");
        parent.put("LLAMA_CACHE", "/tmp/llama-cache");
        parent.put("LLAMA_LOG_LEVEL", "1");
        parent.put("CORTEXJ_HF_TOKEN", "secret-hf");
        parent.put("HF_TOKEN", "secret-token");
        parent.put("CORTEXJ_REMOTE_API_KEY", "secret-remote");
        parent.put("OPENAI_API_KEY", "secret-openai");
        parent.put("CORTEXJ_SERVER_AUTH", "secret-auth");
        parent.put("MAVEN_OPTS", "-Xmx2g");

        java.util.Map<String, String> child =
                ProcessLlamaServerLauncher.allowlistedEnvironment(parent);

        assertFalse(child.containsKey("CORTEXJ_HF_TOKEN"),
                "CORTEXJ_HF_TOKEN must not leak to the child process");
        assertFalse(child.containsKey("HF_TOKEN"),
                "HF_TOKEN must not leak to the child process");
        assertFalse(child.containsKey("CORTEXJ_REMOTE_API_KEY"),
                "CORTEXJ_REMOTE_API_KEY must not leak to the child process");
        assertFalse(child.containsKey("OPENAI_API_KEY"),
                "OPENAI_API_KEY must not leak to the child process");
        assertFalse(child.containsKey("CORTEXJ_SERVER_AUTH"),
                "CORTEXJ_SERVER_AUTH must not leak to the child process");
        assertFalse(child.containsKey("MAVEN_OPTS"),
                "unrelated parent variables must not leak to the child process");
        assertEquals("/usr/bin:/bin", child.get("PATH"));
        assertEquals("/home/tester", child.get("HOME"));
        assertEquals("/tmp", child.get("TMPDIR"));
        assertEquals("/temp", child.get("TEMP"));
        assertEquals("/tmp", child.get("TMP"));
        assertEquals("en_US.UTF-8", child.get("LANG"));
        assertEquals("en_US.UTF-8", child.get("LC_ALL"));
        assertEquals("UTF-8", child.get("LC_CTYPE"));
        assertEquals("/tmp/llama-cache", child.get("LLAMA_CACHE"));
        assertEquals("1", child.get("LLAMA_LOG_LEVEL"));
        for (String name : child.keySet()) {
            assertTrue(isAllowlisted(name), "unexpected non-allowlisted variable: " + name);
        }
    }

    @Test
    void spawnedChildGetsAllowlistedEnvironmentAndExplicitWorkdir() throws Exception {
        Path artifact = Files.createTempFile(tempDir, "fake-model", ".gguf");
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
            final java.util.Map<String, String> capturedEnv =
                    new java.util.LinkedHashMap<String, String>();
            final java.util.concurrent.atomic.AtomicReference<Path> capturedWorkDir =
                    new java.util.concurrent.atomic.AtomicReference<Path>();
            StubbornProcess process = new StubbornProcess() {
                @Override
                public boolean waitFor(long timeout, TimeUnit unit) {
                    running = false;
                    return true;
                }
            };
            ProcessLlamaServerLauncher launcher = new ProcessLlamaServerLauncher(
                    "fake-llama-server", 5_000, null,
                    new ProcessLlamaServerLauncher.ProcessStarter() {
                        @Override
                        public Process start(ProcessBuilder builder) {
                            capturedEnv.putAll(builder.environment());
                            capturedWorkDir.set(builder.directory().toPath());
                            return process;
                        }
                    });
            launcher.launchWithHealthUrl(artifact,
                    "http://127.0.0.1:" + health.getAddress().getPort());

            String[] secrets = {"CORTEXJ_HF_TOKEN", "HF_TOKEN", "CORTEXJ_REMOTE_API_KEY",
                    "OPENAI_API_KEY", "CORTEXJ_SERVER_AUTH"};
            for (String secret : secrets) {
                assertFalse(capturedEnv.containsKey(secret),
                        secret + " must not leak into the child process environment");
            }
            assertTrue(capturedEnv.containsKey("PATH"),
                    "PATH must be allowlisted into the child process environment");
            for (String name : capturedEnv.keySet()) {
                assertTrue(isAllowlisted(name),
                        "unexpected non-allowlisted variable in the child env: " + name);
            }
            assertEquals(artifact.toAbsolutePath().getParent(), capturedWorkDir.get(),
                    "the child must run with an explicit workdir (the model file's parent)");
        } finally {
            health.stop(0);
        }
    }

    private static boolean isAllowlisted(String name) {
        return name.equals("PATH") || name.equals("HOME") || name.equals("TMPDIR")
                || name.equals("TEMP") || name.equals("TMP") || name.equals("LANG")
                || name.startsWith("LC_") || name.startsWith("LLAMA_");
    }
}
