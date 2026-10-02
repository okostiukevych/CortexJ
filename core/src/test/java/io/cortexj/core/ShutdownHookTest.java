package io.cortexj.core;

import io.cortexj.CortexJ;
import io.cortexj.CortexJBuilder;
import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelReference;
import io.cortexj.observability.PlatformEvent;
import io.cortexj.observability.PlatformEventListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShutdownHookTest {

    @TempDir
    Path tempDir;

    @Test
    void shutdownHookIsOptInAndDefaultsToFalse() {
        CortexJBuilder builder = CortexJ.builder();
        assertFalse(builder.isShutdownHook(), "the shutdown hook must be opt-in: default false");
        builder.shutdownHook(true);
        assertTrue(builder.isShutdownHook(), "shutdownHook(true) must be observable on the builder");
    }

    @Test
    void shutdownHookClosesPlatformOnJvmExit() throws Exception {
        Path marker = runHookProbe(true);
        assertTrue(Files.isRegularFile(marker),
                "the registered shutdown hook must close the platform on JVM exit");
    }

    @Test
    void noShutdownHookRegisteredByDefaultOnJvmExit() throws Exception {
        Path marker = runHookProbe(false);
        assertFalse(Files.isRegularFile(marker),
                "without shutdownHook(true) no close hook may be registered on JVM exit");
    }

    private Path runHookProbe(final boolean hook) throws Exception {
        Path marker = tempDir.resolve(hook ? "hook-closed.marker" : "default-unclosed.marker");
        String classpath = System.getProperty("java.class.path");
        String javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(javaBin, "-cp", classpath,
                HookProbeMain.class.getName(), marker.toAbsolutePath().toString(),
                Boolean.toString(hook))
                .redirectErrorStream(true)
                .start();
        final InputStream output = process.getInputStream();
        Thread gobble = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] buffer = new byte[4096];
                    while (output.read(buffer) >= 0) {
                    }
                } catch (IOException ignored) {
                }
            }
        });
        gobble.start();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS),
                "the hook probe subprocess must exit promptly");
        gobble.join(10_000);
        assertEquals(0, process.exitValue(), "the hook probe subprocess must exit cleanly");
        return marker;
    }

    public static final class HookProbeMain {

        public static void main(String[] args) {
            final Path marker = Paths.get(args[0]);
            boolean hook = Boolean.parseBoolean(args[1]);
            CortexJ.builder()
                    .runtimeProvider(new ModelHandleCloseRaceTest.CountingRuntimeProvider())
                    .repositoryProvider(new EndToEndLoadTest.FakeRepository())
                    .eventListener(new PlatformEventListener() {
                        @Override
                        public void onEvent(PlatformEvent event) {
                            if (event.type() == PlatformEvent.Type.PLATFORM_CLOSED) {
                                try {
                                    Files.write(marker, "closed".getBytes(StandardCharsets.UTF_8));
                                } catch (IOException ignored) {
                                }
                            }
                        }
                    })
                    .shutdownHook(hook)
                    .build();
        }
    }
}
