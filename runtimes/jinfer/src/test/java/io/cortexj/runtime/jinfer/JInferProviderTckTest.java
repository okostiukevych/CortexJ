package io.cortexj.runtime.jinfer;

import io.cortexj.inference.GenerationRequest;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ChatMessage;
import io.cortexj.model.ChatRole;
import io.cortexj.runtime.DeviceType;
import io.cortexj.spi.runtime.RuntimeProbeResult;
import io.cortexj.spi.runtime.RuntimeSupport;
import io.cortexj.tck.RuntimeProviderContract;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JInferProviderTckTest extends RuntimeProviderContract {

    private final JInferRuntimeProvider provider = new JInferRuntimeProvider();

    @Override
    protected io.cortexj.spi.runtime.RuntimeProvider provider() {
        return provider;
    }

    @Test
    void probeReportsAvailabilityOrActionableAbsence() {
        RuntimeProbeResult result = provider.probe(probeContext());
        assertNotNull(result.health());
        if (!result.isUsable()) {
            assertNotNull(result.message());
            assertTrue(result.error() != null && !result.error().remediation().isEmpty(),
                    "unavailable JInfer probe must include remediation");
        }
    }

    @Test
    void ggufArtifactEvaluatesAsFullySupported() {
        RuntimeSupport support = provider.evaluate(evaluationRequest(ArtifactFormat.GGUF));
        assertTrue(support.isSupported(), "GGUF must be supported by the JInfer provider");
        assertEquals(DeviceType.CPU, support.proposedDevice());
    }

    @Test
    void onnxArtifactEvaluatesAsUnsupported() {
        RuntimeSupport support = provider.evaluate(evaluationRequest(ArtifactFormat.ONNX));
        assertTrue(!support.isSupported(), "ONNX must not be claimed by the JInfer provider");
        assertTrue(!support.reasons().isEmpty(), "unsupported evaluation must carry reasons");
    }

    @Test
    void promptOnlyRequestMapsToSingleUserMessage() {
        List<com.qxotic.jinfer.chat.Message> messages =
                JInferRuntimeProvider.toJInferMessages(GenerationRequest.prompt("hello"));
        assertEquals(1, messages.size());
        assertEquals(com.qxotic.jinfer.chat.Role.USER, messages.get(0).role());
        assertEquals("hello", messages.get(0).text());
    }

    @Test
    void chatRolesMapOntoJInferRoles() {
        GenerationRequest request = GenerationRequest.builder()
                .message(ChatMessage.of(ChatRole.SYSTEM, "sys"))
                .message(ChatMessage.of(ChatRole.USER, "hi"))
                .message(ChatMessage.of(ChatRole.ASSISTANT, "hey"))
                .build();
        List<com.qxotic.jinfer.chat.Message> messages = JInferRuntimeProvider.toJInferMessages(request);
        assertEquals(3, messages.size());
        assertEquals(com.qxotic.jinfer.chat.Role.SYSTEM, messages.get(0).role());
        assertEquals(com.qxotic.jinfer.chat.Role.USER, messages.get(1).role());
        assertEquals(com.qxotic.jinfer.chat.Role.ASSISTANT, messages.get(2).role());
    }

    @Test
    void missingArtifactFailsWithTypedLoadError() {        java.nio.file.Path bogus = java.nio.file.Paths.get("definitely-not-a-model-"
                + System.nanoTime() + ".gguf");
        io.cortexj.error.CortexJException failure = assertThrows(
                io.cortexj.error.CortexJException.class,
                () -> provider.open(new io.cortexj.spi.runtime.RuntimeOpenRequest(
                        io.cortexj.model.ModelDescriptor.builder("tck/jinfer").build(),
                        null, bogus, DeviceType.CPU,
                        java.util.Collections.<String, Object>emptyMap(),
                        java.util.Collections.<String, String>emptyMap(),
                        testEnvironment(), testHardware(false), null, null))
                        .load(new io.cortexj.spi.runtime.RuntimeModelRequest(
                                io.cortexj.model.ModelDescriptor.builder("tck/jinfer").build(),
                                null, bogus, DeviceType.CPU,
                                java.util.Collections.<String, Object>emptyMap())));
        assertEquals("CORTEXJ-LOAD-8001", failure.code());
    }

    @Test
    void java25EnvironmentMakesProbeUsable() {
        io.cortexj.hardware.EnvironmentProfile java25 = new io.cortexj.hardware.EnvironmentProfile(
                "TCKVendor", "TCKJvm", 25, "25.0.0", "linux", "x86_64",
                8, 2L * 1024L * 1024L * 1024L, 16L * 1024L * 1024L * 1024L,
                8L * 1024L * 1024L * 1024L, true, null);
        io.cortexj.spi.runtime.RuntimeProbeContext context =
                new io.cortexj.spi.runtime.RuntimeProbeContext(
                        java25, testHardware(true), false,
                        java.util.Collections.<String, String>emptyMap());
        RuntimeProbeResult result = provider.probe(context);
        assertNotNull(result.health());
        org.junit.jupiter.api.Assumptions.assumeTrue(result.isUsable(),
                "JInfer API not loadable in this JVM - lifecycle contract skipped loudly: "
                        + result.message());
        io.cortexj.spi.runtime.ModelRuntime runtime = provider.open(openRequest(null));
        runtime.close();
        runtime.close();
        runtime.close();
    }

    @Test
    void java21EnvironmentReportsJvmIncompatibilityWithRemediation() {
        RuntimeProbeResult result = provider.probe(probeContext());
        assertTrue(!result.isUsable(), "synthetic TCK env is Java 21 - JInfer must decline");
        assertNotNull(result.error());
        assertTrue(!result.error().remediation().isEmpty(),
                "JVM-incompatible probe must include remediation");
    }
}
