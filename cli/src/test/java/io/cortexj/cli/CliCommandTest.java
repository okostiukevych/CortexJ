package io.cortexj.cli;

import io.cortexj.CortexJ;
import io.cortexj.error.CortexJException;
import io.cortexj.model.ModelReference;
import io.cortexj.server.CortexJServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliCommandTest {

    @Test
    void doctorCommandRunsOnCurrentJvm() {
        int status = CortexJMain.run(new String[]{"doctor"});
        assertEquals(0, status);
    }

    @Test
    void runtimesCommandPrintsRegisteredProviders() {
        int status = CortexJMain.run(new String[]{"runtimes"});
        assertEquals(0, status);
    }

    @Test
    void hardwareCommandPrintsProfile() {
        int status = CortexJMain.run(new String[]{"hardware"});
        assertEquals(0, status);
    }

    @Test
    void unknownCommandFails() {
        assertEquals(1, CortexJMain.run(new String[]{"definitely-not-a-command"}));
    }

    @Test
    void inspectUnknownModelReportsGracefully() {
        int status = CortexJMain.run(new String[]{"inspect", "no-such-owner/no-such-model-xyz"});
        assertTrue(status == 0 || status == 3);
    }

    @Test
    void cacheHomeHonorsCortexjHomeSysprop(@TempDir Path tempDir) {
        String previous = System.getProperty("cortexj.home");
        System.setProperty("cortexj.home", tempDir.toString());
        try {
            assertEquals(tempDir.resolve("cache"), CortexJMain.defaultCache().root());
        } finally {
            restoreHomeProperty(previous);
        }
    }

    @Test
    void cacheHomeFallsBackToUserHomeWhenUnset() {
        String previous = System.getProperty("cortexj.home");
        System.clearProperty("cortexj.home");
        try {
            Path expected = java.nio.file.Paths.get(System.getProperty("user.home", "."), ".cortexj");
            assertEquals(expected.resolve("cache"), CortexJMain.cacheHome().resolve("cache"));
        } finally {
            restoreHomeProperty(previous);
        }
    }

    @Test
    void cacheListOperatesOnPlatformHome(@TempDir Path tempDir) throws Exception {
        String previous = System.getProperty("cortexj.home");
        System.setProperty("cortexj.home", tempDir.toString());
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, "UTF-8"));
        try {
            int status = CortexJMain.run(new String[]{"cache", "list"});
            assertEquals(0, status);
        } finally {
            System.setOut(originalOut);
            restoreHomeProperty(previous);
        }
        assertTrue(captured.toString("UTF-8").contains(tempDir.resolve("cache").toString()));
    }

    @Test
    void runServerShutdownHookClosesPlatformWhenServerMissing() {
        CortexJ platform = CortexJ.builder().build();
        AtomicReference<CortexJServer> serverRef = new AtomicReference<CortexJServer>();
        CortexJMain.runServerShutdownHook(serverRef, platform);
        assertThrows(CortexJException.class,
                () -> platform.models().load(ModelReference.of("any/model")));
    }

    @Test
    void serverShutdownHookThreadRunsBodyAndClosesPlatform() throws Exception {
        CortexJ platform = CortexJ.builder().build();
        AtomicReference<CortexJServer> serverRef = new AtomicReference<CortexJServer>();
        Thread hook = CortexJMain.serverShutdownHook(serverRef, platform);
        hook.start();
        hook.join(10_000L);
        assertFalse(hook.isAlive());
        assertThrows(CortexJException.class,
                () -> platform.models().load(ModelReference.of("any/model")));
    }

    @Test
    void authDeprecationWarningRecommendsEnvVar() throws Exception {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, "UTF-8"));
        try {
            CortexJMain.warnCommandLineAuth();
        } finally {
            System.setErr(originalErr);
        }
        assertTrue(captured.toString("UTF-8").contains("CORTEXJ_SERVER_AUTH"));
    }

    @Test
    void convertRepositoryFailureReportsToStderr(@TempDir Path tempDir) throws Exception {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, "UTF-8"));
        try {
            int status = CortexJMain.run(new String[]{"convert", "/nonexistent-cortexj-model/model",
                    "--to", "f16", "--out", tempDir.toString()});
            assertEquals(2, status);
        } finally {
            System.setErr(originalErr);
        }
        assertTrue(captured.toString("UTF-8").contains("convert failed"));
    }

    private static void restoreHomeProperty(String previous) {
        if (previous == null) {
            System.clearProperty("cortexj.home");
        } else {
            System.setProperty("cortexj.home", previous);
        }
    }
}
