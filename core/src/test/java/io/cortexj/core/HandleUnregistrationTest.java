package io.cortexj.core;

import io.cortexj.CortexJ;
import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelLifecycleState;
import io.cortexj.model.ModelReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HandleUnregistrationTest {

    @TempDir
    Path workDir;

    @Test
    void openHandlesEmptyAfterLoadCloseChurn() {
        ModelHandleCloseRaceTest.CountingRuntimeProvider provider =
                new ModelHandleCloseRaceTest.CountingRuntimeProvider();
        CortexJ platform = CortexJ.builder()
                .runtimeProvider(provider)
                .repositoryProvider(new EndToEndLoadTest.FakeRepository())
                .workingDirectory(workDir)
                .build();
        try {
            DefaultModels models = (DefaultModels) platform.models();
            for (int i = 0; i < 25; i++) {
                ModelHandle handle = models.load(ModelReference.of("e2e/test-model"));
                handle.close();
                assertEquals(ModelLifecycleState.CLOSED, handle.state());
            }
            assertEquals(0, models.openHandleCount(),
                    "every closed handle must be unregistered: openHandles must stay empty under churn");
        } finally {
            platform.close();
        }
    }

    @Test
    void closeAllAfterChurnClosesExactlyLiveHandles() {
        ModelHandleCloseRaceTest.CountingRuntimeProvider provider =
                new ModelHandleCloseRaceTest.CountingRuntimeProvider();
        CortexJ platform = CortexJ.builder()
                .runtimeProvider(provider)
                .repositoryProvider(new EndToEndLoadTest.FakeRepository())
                .workingDirectory(workDir)
                .build();
        try {
            DefaultModels models = (DefaultModels) platform.models();
            List<ModelHandle> handles = new ArrayList<ModelHandle>();
            for (int i = 0; i < 8; i++) {
                handles.add(models.load(ModelReference.of("e2e/test-model")));
            }
            for (int i = 0; i < 5; i++) {
                handles.get(i).close();
            }
            assertEquals(3, models.openHandleCount(),
                    "only the 3 live handles may remain registered");
            platform.close();
            for (int i = 5; i < 8; i++) {
                assertEquals(ModelLifecycleState.CLOSED, handles.get(i).state(),
                        "closeAll() must close every still-live handle");
            }
            assertEquals(0, models.openHandleCount());
            for (ModelHandleCloseRaceTest.CountingRuntimeModel model : provider.models) {
                assertEquals(1, model.closeCount.get(),
                        "each runtime model must be closed exactly once (no double-close)");
            }
        } finally {
            platform.close();
        }
    }

    @Test
    void concurrentCloseAndLoadKeepsRegistryConsistent() throws Exception {
        ModelHandleCloseRaceTest.CountingRuntimeProvider provider =
                new ModelHandleCloseRaceTest.CountingRuntimeProvider();
        CortexJ platform = CortexJ.builder()
                .runtimeProvider(provider)
                .repositoryProvider(new EndToEndLoadTest.FakeRepository())
                .workingDirectory(workDir)
                .build();
        try {
            DefaultModels models = (DefaultModels) platform.models();
            List<ModelHandle> handles = new ArrayList<ModelHandle>();
            final RuntimeException[] failure = new RuntimeException[1];
            Thread loader = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        for (int i = 0; i < 15; i++) {
                            ModelHandle handle = models.load(ModelReference.of("e2e/test-model"));
                            synchronized (handles) {
                                handles.add(handle);
                            }
                        }
                    } catch (RuntimeException e) {
                        failure[0] = e;
                    }
                }
            });
            loader.start();
            Thread closer = new Thread(new Runnable() {
                @Override
                public void run() {
                    for (int round = 0; round < 50; round++) {
                        synchronized (handles) {
                            if (!handles.isEmpty()) {
                                handles.remove(0).close();
                            }
                        }
                        try {
                            Thread.sleep(1);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            });
            closer.start();
            loader.join(20_000);
            closer.join(20_000);
            if (failure[0] != null) {
                throw failure[0];
            }
            platform.close();
            assertEquals(0, models.openHandleCount(),
                    "registry must be empty after close(): no handle may be left behind");
            for (ModelHandleCloseRaceTest.CountingRuntimeModel model : provider.models) {
                assertEquals(1, model.closeCount.get(),
                        "no runtime model may be closed twice under concurrent close/load");
            }
        } finally {
            platform.close();
        }
    }
}
