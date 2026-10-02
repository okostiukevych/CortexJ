package io.cortexj.core;

import io.cortexj.CortexJ;
import io.cortexj.error.NativeRuntimeException;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelLifecycleState;
import io.cortexj.model.ModelReference;
import io.cortexj.observability.PlatformEvent;
import io.cortexj.observability.PlatformEventListener;
import io.cortexj.runtime.DeviceType;
import io.cortexj.runtime.RuntimeDescriptor;
import io.cortexj.runtime.RuntimeId;
import io.cortexj.spi.runtime.ModelRuntime;
import io.cortexj.spi.runtime.RuntimeModel;
import io.cortexj.spi.runtime.RuntimeModelRequest;
import io.cortexj.spi.runtime.RuntimeOpenRequest;
import io.cortexj.spi.runtime.RuntimeProbeContext;
import io.cortexj.spi.runtime.RuntimeProbeResult;
import io.cortexj.spi.runtime.RuntimeProvider;
import io.cortexj.spi.runtime.RuntimeSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FallbackChainTest {

    @TempDir
    Path workDir;

    static final class FailingOpenRuntimeProvider implements RuntimeProvider {

        private final RuntimeId id = RuntimeId.of("aaa-fail-open-runtime");
        final AtomicInteger openAttempts = new AtomicInteger();

        @Override
        public RuntimeId id() {
            return id;
        }

        @Override
        public RuntimeDescriptor descriptor() {
            return RuntimeDescriptor.builder(id)
                    .version("1.0")
                    .format(ArtifactFormat.GGUF)
                    .device(DeviceType.CPU)
                    .task(io.cortexj.model.TaskCapability.TEXT_GENERATION)
                    .maturity(RuntimeDescriptor.Maturity.STABLE)
                    .build();
        }

        @Override
        public RuntimeProbeResult probe(RuntimeProbeContext context) {
            return RuntimeProbeResult.available("failing-open runtime ready");
        }

        @Override
        public RuntimeSupport evaluate(io.cortexj.spi.runtime.RuntimeEvaluationRequest request) {
            return RuntimeSupport.full(DeviceType.CPU);
        }

        @Override
        public ModelRuntime open(RuntimeOpenRequest request) {
            openAttempts.incrementAndGet();
            throw new NativeRuntimeException("CORTEXJ-RUNTIME-0001",
                    "simulated native runtime open failure");
        }
    }

    @Test
    void nativeOpenFailureFallsThroughToNextCandidate() {
        FailingOpenRuntimeProvider failing = new FailingOpenRuntimeProvider();
        ModelHandleCloseRaceTest.CountingRuntimeProvider fallback =
                new ModelHandleCloseRaceTest.CountingRuntimeProvider();
        final List<PlatformEvent.Type> fallbackEvents = new CopyOnWriteArrayList<PlatformEvent.Type>();
        CortexJ platform = CortexJ.builder()
                .runtimeProvider(failing)
                .runtimeProvider(fallback)
                .repositoryProvider(new EndToEndLoadTest.FakeRepository())
                .eventListener(new PlatformEventListener() {
                    @Override
                    public void onEvent(PlatformEvent event) {
                        if (event.type() == PlatformEvent.Type.FALLBACK_APPLIED) {
                            fallbackEvents.add(event.type());
                        }
                    }
                })
                .workingDirectory(workDir)
                .build();
        try {
            ModelHandle handle = platform.models().load(ModelReference.of("e2e/test-model"));
            try {
                assertEquals(ModelLifecycleState.READY, handle.state(),
                        "an open() failure must participate in the fallback chain instead of failing the load");
                assertEquals("aaa-fail-open-runtime", handle.plan().selected().runtimeId(),
                        "the failing-open runtime must have been the selected candidate");
                assertEquals(1, failing.openAttempts.get(),
                        "the failing runtime must have been opened exactly once");
                assertEquals(1, fallback.models.size(),
                        "the fallback runtime must have loaded the model");
                DefaultModelHandle defaultHandle = (DefaultModelHandle) handle;
                assertNotNull(defaultHandle.fallbackNote(),
                        "the handle must be marked as a fallback after falling through");
                assertTrue(defaultHandle.fallbackNote().contains("close-race-runtime"),
                        "the fallback note must name the fallback runtime: "
                                + defaultHandle.fallbackNote());
                assertTrue(!fallbackEvents.isEmpty(),
                        "a FALLBACK_APPLIED warning must be published when falling through");
            } finally {
                handle.close();
            }
        } finally {
            platform.close();
        }
    }
}
