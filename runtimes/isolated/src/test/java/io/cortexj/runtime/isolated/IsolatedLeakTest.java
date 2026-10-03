package io.cortexj.runtime.isolated;

import io.cortexj.inference.InferenceRequest;
import io.cortexj.spi.runtime.ModelRuntime;
import io.cortexj.spi.runtime.RuntimeModel;
import io.cortexj.spi.runtime.RuntimeProbeResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Leak contract: provider.close() must leave no worker process behind, no
 * reader thread blocked on the dead pipe, and no pending request futures.
 */
class IsolatedLeakTest {

    @Test
    void closeLeavesNoChildProcessOrOrphanedThreads() throws Exception {
        Path artifact = Files.createTempFile("isolated-leak-model", ".onnx");
        Files.write(artifact, "stub".getBytes(StandardCharsets.UTF_8));

        IsolatedRuntimeProvider provider = new IsolatedRuntimeProvider("stub");
        try {
            RuntimeProbeResult probe = provider.probe(IsolatedProviderRoundTripTest.probeContext());
            assertTrue(probe.isUsable(), "probe must spawn the child: " + probe.message());
            WorkerChannel channel = provider.currentChannelForTesting();
            assertNotNull(channel);
            Process child = channel.processForTesting();
            assertTrue(isAlive(child), "the child must be alive before close");

            // an open session with a loaded model must be closed out by
            // provider.close() as well (token close + shutdown op + ladder)
            ModelRuntime runtime = provider.open(IsolatedProviderRoundTripTest.openRequest());
            RuntimeModel model = runtime.load(IsolatedProviderRoundTripTest.loadRequest(artifact));

            provider.close();

            // the child process is fully gone (shutdown op + destroy ladder)
            assertGone(child);
            // the reader thread reached EOF and exited: no orphaned threads
            assertTrue(joinFinished(channel.readerThreadForTesting()),
                    "the channel reader thread must exit after close");
            // no request futures are stuck pending
            Iterator<Long> pending = channel.pendingIdsForTesting();
            assertFalse(pending.hasNext(), "no pending requests may survive close");

            // idempotent: a second close is a no-op
            provider.close();
            assertFalse(isAlive(child));

            // a fresh provider respawns and closes cleanly (nothing leaks
            // that would prevent a second worker on the same machine)
            IsolatedRuntimeProvider second = new IsolatedRuntimeProvider("stub");
            Process secondChild = null;
            try {
                RuntimeProbeResult respawned = second.probe(IsolatedProviderRoundTripTest.probeContext());
                assertTrue(respawned.isUsable(), "a fresh provider must respawn: " + respawned.message());
                secondChild = second.currentChannelForTesting().processForTesting();
                ModelRuntime freshRuntime = second.open(IsolatedProviderRoundTripTest.openRequest());
                try {
                    RuntimeModel freshModel = freshRuntime.load(IsolatedProviderRoundTripTest.loadRequest(artifact));
                    assertEquals("stub:fresh", freshModel
                            .execute(InferenceRequest.builder().input("fresh").build()).singleOutput());
                } finally {
                    freshRuntime.close();
                }
            } finally {
                second.close();
                if (secondChild != null) {
                    assertGone(secondChild);
                }
            }
        } finally {
            provider.close();
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

    private static void assertGone(Process process) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000L;
        while (System.currentTimeMillis() < deadline) {
            if (!isAlive(process)) {
                return;
            }
            Thread.sleep(50L);
        }
        process.destroyForcibly();
        throw new AssertionError("worker process is still alive after provider close");
    }

    private static boolean joinFinished(Thread thread) throws InterruptedException {
        thread.join(10_000L);
        return !thread.isAlive();
    }
}
