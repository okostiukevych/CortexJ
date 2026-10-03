package io.cortexj.runtime.isolated;

import io.cortexj.error.InferenceException;
import io.cortexj.inference.InferenceRequest;
import io.cortexj.spi.runtime.ModelRuntime;
import io.cortexj.spi.runtime.RuntimeModel;
import io.cortexj.spi.runtime.RuntimeProbeResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Child-crash resilience: the worker process is destroyForcibly()'d
 * mid-execute; the parent must surface a typed InferenceException (never a
 * JVM-level crash or hang), tear the dead channel down, and respawn a fresh
 * child on the next probe.
 */
class IsolatedChildCrashTest {

    private static IsolatedRuntimeProvider provider;
    private static java.nio.file.Path artifact;

    @BeforeAll
    static void setUp() throws Exception {
        provider = new IsolatedRuntimeProvider("stub");
        artifact = java.nio.file.Files.createTempFile("isolated-crash-model", ".onnx");
        java.nio.file.Files.write(artifact, "stub".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @AfterAll
    static void tearDown() {
        provider.close();
    }

    @Test
    void crashingChildFailsRequestsTypedAndProviderRespawns() throws Exception {
        RuntimeProbeResult probe = provider.probe(IsolatedProviderRoundTripTest.probeContext());
        assertTrue(probe.isUsable(), "probe must succeed before the crash: " + probe.message());

        ModelRuntime runtime = provider.open(IsolatedProviderRoundTripTest.openRequest());
        try {
            RuntimeModel model = runtime.load(IsolatedProviderRoundTripTest.loadRequest(artifact));
            assertEquals("stub:warmup", model.execute(InferenceRequest.builder().input("warmup").build())
                    .singleOutput(), "warmup execute must succeed before the crash");

            WorkerChannel channel = provider.currentChannelForTesting();
            assertNotNull(channel, "the probe must have spawned a channel");
            Process child = channel.processForTesting();

            // slow execute (the stub sleeps 60s on parameter slow=true):
            // kill the child while the request is in flight
            final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
            final CountDownLatch done = new CountDownLatch(1);
            Thread request = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        model.execute(InferenceRequest.builder()
                                .input("doomed").parameter("slow", Boolean.TRUE).build());
                        failure.set(new AssertionError("execute unexpectedly succeeded after a child crash"));
                    } catch (Throwable typed) {
                        failure.set(typed);
                    } finally {
                        done.countDown();
                    }
                }
            }, "crash-test-execute");
            request.setDaemon(true);
            request.start();

            Thread.sleep(1_000L); // let the request reach the child
            child.destroyForcibly();

            assertTrue(done.await(30, TimeUnit.SECONDS),
                    "execute must fail promptly after the child dies, not hang");
            Throwable reported = failure.get();
            assertNotNull(reported, "execute must fail, got success");
            assertTrue(reported instanceof InferenceException,
                    "the failure must be a typed InferenceException, got: " + reported);
            InferenceException typed = (InferenceException) reported;
            assertEquals(io.cortexj.ErrorCode.INFERENCE_FAILED, typed.code());
            assertTrue(typed.getMessage().contains("worker"),
                    "the failure must name the worker process: " + typed.getMessage());
            assertTrue(!typed.remediation().isEmpty(),
                    "the failure must carry a remediation hint");

            // the provider marked the channel dead and tore it down
            assertTrue(channel.isDead(), "the crashed channel must be marked dead");
            // the old process is gone (the ladder ran during teardown or now)
            assertProcessGone(child);

            // the next probe respawns a fresh child and succeeds
            RuntimeProbeResult respawned = provider.probe(IsolatedProviderRoundTripTest.probeContext());
            assertTrue(respawned.isUsable(),
                    "probe must respawn the worker after a crash: " + respawned.message());
            assertNotSame(child, provider.currentChannelForTesting().processForTesting(),
                    "the respawn must be a new process");

            // a fresh session fully works on the respawned child (the old
            // session died with the crashed channel - documented v1 behavior)
            ModelRuntime respawnedRuntime = provider.open(IsolatedProviderRoundTripTest.openRequest());
            try {
                RuntimeModel fresh = respawnedRuntime.load(IsolatedProviderRoundTripTest.loadRequest(artifact));
                assertEquals("stub:after-crash",
                        fresh.execute(InferenceRequest.builder().input("after-crash").build()).singleOutput());
            } finally {
                respawnedRuntime.close();
            }
        } finally {
            runtime.close();
        }
    }

    private static void assertProcessGone(Process process) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                process.exitValue();
                return;
            } catch (IllegalThreadStateException stillRunning) {
                Thread.sleep(50L);
            }
        }
        process.destroyForcibly();
        throw new AssertionError("crashed worker process is still alive after the teardown ladder");
    }
}
