package io.cortexj.core;

import io.cortexj.CortexJ;
import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelLifecycleState;
import io.cortexj.model.ModelReference;
import io.cortexj.planner.ExecutionPlan;
import io.cortexj.planner.ExecutionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelHandleWaitForReadyTest {

    @TempDir
    Path workDir;

    private CortexJ platform() {
        return CortexJ.builder()
                .runtimeProvider(new ModelHandleCloseRaceTest.CountingRuntimeProvider())
                .repositoryProvider(new EndToEndLoadTest.FakeRepository())
                .workingDirectory(workDir)
                .build();
    }

    private DefaultModelHandle unpublishedHandle(CortexJ platform) {
        ExecutionPlan plan = platform.planner().plan(
                ExecutionRequest.forModel(ModelReference.of("e2e/test-model")));
        return new DefaultModelHandle(
                plan.resolvedModel().descriptor(), plan,
                new ModelHandleCloseRaceTest.CountingRuntimeModel(), null,
                (DefaultCortexJ) platform, plan.selected());
    }

    @Test
    void waitForReadyBlocksUntilReadyThenReturns() throws Exception {
        CortexJ platform = platform();
        try {
            DefaultModelHandle handle = unpublishedHandle(platform);
            assertEquals(ModelLifecycleState.DISCOVERED, handle.state(),
                    "a fresh handle starts in DISCOVERED - the load pipeline drives the states");

            final RuntimeException[] waiterFailure = new RuntimeException[1];
            Thread waiter = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        handle.waitForReady();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (RuntimeException e) {
                        waiterFailure[0] = e;
                    }
                }
            });
            waiter.start();
            Thread.sleep(150);
            assertTrue(waiter.isAlive(), "waitForReady must block while the handle is not ready");
            handle.lifecycleTo(ModelLifecycleState.RESOLVING);
            handle.lifecycleTo(ModelLifecycleState.LOADING);
            Thread.sleep(100);
            assertTrue(waiter.isAlive(),
                    "waitForReady must keep blocking through intermediate states");
            handle.lifecycleTo(ModelLifecycleState.READY);
            waiter.join(5_000);
            assertFalse(waiter.isAlive(), "waitForReady must return once the handle is READY");
            if (waiterFailure[0] != null) {
                throw waiterFailure[0];
            }
            handle.waitForReady();
        } finally {
            platform.close();
        }
    }

    @Test
    void waitForReadyThrowsOnFailedHandle() throws Exception {
        CortexJ platform = platform();
        try {
            DefaultModelHandle handle = unpublishedHandle(platform);
            handle.lifecycleTo(ModelLifecycleState.RESOLVING);
            handle.lifecycleTo(ModelLifecycleState.FAILED);
            assertThrows(IllegalStateException.class, handle::waitForReady);
        } finally {
            platform.close();
        }
    }

    @Test
    void waitForReadyThrowsOnClosedHandle() throws Exception {
        CortexJ platform = platform();
        try {
            DefaultModelHandle handle = unpublishedHandle(platform);
            handle.lifecycleTo(ModelLifecycleState.RESOLVING);
            handle.lifecycleTo(ModelLifecycleState.READY);
            handle.waitForReady();
            handle.close();
            assertEquals(ModelLifecycleState.CLOSED, handle.state());
            assertThrows(IllegalStateException.class, handle::waitForReady);
        } finally {
            platform.close();
        }
    }

    @Test
    void loadPipelineLeavesHandleReadyAndWaitIsImmediate() throws Exception {
        CortexJ platform = platform();
        try {
            ModelHandle handle = platform.models().load(ModelReference.of("e2e/test-model"));
            assertEquals(ModelLifecycleState.READY, handle.state(),
                    "load() is synchronous: the returned handle must be READY");
            long started = System.nanoTime();
            handle.waitForReady();
            long waitedMillis = (System.nanoTime() - started) / 1_000_000L;
            assertTrue(waitedMillis < 1_000, "waitForReady must return immediately for a READY handle");
            handle.close();
        } finally {
            platform.close();
        }
    }

    @Test
    void preparingMayTransitionToDownloadingForConversionSourceDownloads() {
        assertTrue(ModelLifecycleState.PREPARING.canTransitionTo(ModelLifecycleState.DOWNLOADING),
                "a conversion (PREPARING) may need to download its source artifact");
    }
}
