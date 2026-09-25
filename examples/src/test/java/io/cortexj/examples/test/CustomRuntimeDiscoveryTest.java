package io.cortexj.examples.test;

import io.cortexj.CortexJ;
import io.cortexj.model.ModelReference;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomRuntimeDiscoveryTest {

    @Test
    void thirdPartyRuntimeIsDiscoveredWithoutCoreChanges() {
        // spec 84 / AC-RUNTIME-004: a new runtime joins the platform through ServiceLoader
        // alone; the planner sees it via descriptor/SPI (AC-RUNTIME-005)
        AtomicBoolean found = new AtomicBoolean(false);
        for (io.cortexj.spi.runtime.RuntimeProvider provider
                : ServiceLoader.load(io.cortexj.spi.runtime.RuntimeProvider.class)) {
            if ("company-runtime".equals(provider.id().value())) {
                found.set(true);
            }
        }
        assertTrue(found.get(), "CustomRuntimeProvider must be discoverable via ServiceLoader");
    }

    @Test
    void plannerSeesCustomRuntimeThroughDescriptor() {
        try (CortexJ platform = CortexJ.builder().build()) {
            boolean listed = false;
            for (io.cortexj.runtime.RuntimeDescriptor descriptor : platform.runtimes()) {
                if ("company-runtime".equals(descriptor.id().value())) {
                    listed = true;
                }
            }
            assertTrue(listed, "platform.runtimes() must expose the custom provider");
            // and the support report is inspectable without loading a model (spec 92)
            try {
                platform.support().inspect(ModelReference.of("Qwen/Qwen3-8B"));
            } catch (RuntimeException expectedOnOfflineMachine) {
                // resolution may fail on this machine; discovery is what this test asserts
            }
        }
    }
}
