package io.cortexj.runtime.llamacpp;

import io.cortexj.error.NativeRuntimeException;
import io.cortexj.spi.runtime.RuntimeProbeResult;
import io.cortexj.tck.RuntimeProviderContract;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlamaCppProviderTckTest extends RuntimeProviderContract {

    private final LlamaCppProvider provider = new LlamaCppProvider();

    @Override
    protected io.cortexj.spi.runtime.RuntimeProvider provider() {
        return provider;
    }

    @Test
    void probeWithoutNativeLibraryIsUnavailableWithRemediation() {
        System.clearProperty(LlamaCppProvider.LIB_PROPERTY);
        RuntimeProbeResult result = provider.probe(probeContext());
        if (!result.isUsable()) {
            assertEquals(io.cortexj.runtime.RuntimeHealth.UNAVAILABLE, result.health());
            assertNotNull(result.error());
            assertTrue(result.error().remediation().size() >= 1);
            assertTrue(result.error().remediation().get(0).contains("llama-server"));
        }
    }

    @Test
    void probeDetectsExplicitLibraryPath() throws Exception {
        Path dir = Files.createTempDirectory("llamacpp-tck");
        File lib = dir.resolve("libllama.so").toFile();
        try (FileOutputStream out = new FileOutputStream(lib)) {
            out.write(1);
        }
        System.setProperty(LlamaCppProvider.LIB_PROPERTY, lib.getAbsolutePath());
        try {
            RuntimeProbeResult result = provider.probe(probeContext());
            assertEquals(io.cortexj.runtime.RuntimeHealth.AVAILABLE, result.health());
        } finally {
            System.clearProperty(LlamaCppProvider.LIB_PROPERTY);
            Files.deleteIfExists(lib.toPath());
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void loadWithoutBridgeThrowsActionableNativeError() throws Exception {
        System.clearProperty(LlamaCppProvider.LIB_PROPERTY);
        Path gguf = Files.createTempFile("model", ".gguf");
        try {
            NativeRuntimeException expected = assertThrows(NativeRuntimeException.class,
                    () -> provider.open(new io.cortexj.spi.runtime.RuntimeOpenRequest(
                            io.cortexj.model.ModelDescriptor.builder("tck/llama").build(),
                            null, gguf, io.cortexj.runtime.DeviceType.CPU,
                            java.util.Collections.<String, Object>emptyMap(),
                            java.util.Collections.<String, String>emptyMap(),
                            testEnvironment(), testHardware(false), null, null)));
            assertEquals("CORTEXJ-NATIVE-6001", expected.code());
            assertTrue(!expected.remediation().isEmpty());
            String remediation = expected.remediation().get(0);
            assertTrue(remediation.contains("llama-server") || remediation.contains("JNI"),
                    "remediation must offer the llama-server/openai-remote alternative");
        } finally {
            Files.deleteIfExists(gguf);
        }
    }
}
