package io.cortexj.runtime.onnx;

import io.cortexj.inference.EmbeddingRequest;
import io.cortexj.inference.EmbeddingResponse;
import io.cortexj.spi.runtime.ModelRuntime;
import io.cortexj.spi.runtime.RuntimeModel;
import io.cortexj.spi.runtime.RuntimeModelRequest;
import io.cortexj.spi.runtime.RuntimeOpenRequest;
import io.cortexj.spi.runtime.RuntimeProbeResult;
import io.cortexj.tck.RuntimeProviderContract;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A real BERT-style failure as a unit test: graphs that consume auxiliary
 * inputs must get provider-fed defaults (ones for masks, zeros otherwise),
 * not an ORT "Missing Input" failure.
 */
class OnnxAuxiliaryInputsTest {

    @Test
    void attentionMaskIsFedWithOnes() throws Exception {
        RuntimeProbeResult probe = new OnnxRuntimeProvider().probe(RuntimeProviderContract.probeContext());
        org.junit.jupiter.api.Assumptions.assumeTrue(probe.isUsable(),
                "ONNX native runtime not available on this machine");
        Path modelPath = OnnxTestModels.echoModel("attention_mask");
        try {
            float[] vector = embed(modelPath, "hello world");
            assertNotNull(vector);
            assertArrayEquals(new float[]{1.0f, 1.0f}, vector, 0.0f);
        } finally {
            Files.deleteIfExists(modelPath);
        }
    }

    @Test
    void tokenTypeIdsAreFedWithZeros() throws Exception {
        RuntimeProbeResult probe = new OnnxRuntimeProvider().probe(RuntimeProviderContract.probeContext());
        org.junit.jupiter.api.Assumptions.assumeTrue(probe.isUsable(),
                "ONNX native runtime not available on this machine");
        Path modelPath = OnnxTestModels.echoModel("token_type_ids");
        try {
            float[] vector = embed(modelPath, "hello world");
            assertNotNull(vector);
            assertArrayEquals(new float[]{0.0f, 0.0f}, vector, 0.0f);
        } finally {
            Files.deleteIfExists(modelPath);
        }
    }

    private static float[] embed(Path modelPath, String text) {
        OnnxRuntimeProvider provider = new OnnxRuntimeProvider();
        ModelRuntime runtime = provider.open(new RuntimeOpenRequest(
                io.cortexj.model.ModelDescriptor.builder("tck/onnx-aux").build(),
                null, modelPath, io.cortexj.runtime.DeviceType.CPU,
                java.util.Collections.<String, Object>emptyMap(),
                java.util.Collections.<String, String>emptyMap(),
                RuntimeProviderContract.testEnvironment(),
                RuntimeProviderContract.testHardware(false), null, null));
        try {
            RuntimeModel loaded = runtime.load(new RuntimeModelRequest(
                    io.cortexj.model.ModelDescriptor.builder("tck/onnx-aux").build(),
                    null, modelPath, io.cortexj.runtime.DeviceType.CPU,
                    java.util.Collections.<String, Object>emptyMap()));
            try {
                EmbeddingResponse response = ((io.cortexj.inference.EmbeddingCapability) loaded)
                        .embed(EmbeddingRequest.text(text));
                return response.singleVector();
            } finally {
                loaded.close();
            }
        } finally {
            runtime.close();
        }
    }
}
