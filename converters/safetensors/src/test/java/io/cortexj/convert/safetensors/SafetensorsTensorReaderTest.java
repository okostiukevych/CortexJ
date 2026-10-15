package io.cortexj.convert.safetensors;

import io.cortexj.convert.safetensors.test.SafetensorsFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafetensorsTensorReaderTest {

    @TempDir
    Path temp;

    @Test
    void readsF32F16AndBf16Tensors() throws IOException {
        Map<String, SafetensorsFixture.Tensor> tensors =
                new LinkedHashMap<String, SafetensorsFixture.Tensor>();
        tensors.put("a", new SafetensorsFixture.Tensor("F32", new long[]{2, 2},
                SafetensorsFixture.f32(1f, 2f, 3f, 4f)));
        tensors.put("b", new SafetensorsFixture.Tensor("F16", new long[]{3},
                SafetensorsFixture.f16(1f, 2f, -1f)));
        tensors.put("c", new SafetensorsFixture.Tensor("BF16", new long[]{2},
                SafetensorsFixture.bf16(1f, -2.5f)));
        Path file = temp.resolve("model.safetensors");
        SafetensorsFixture.write(file, tensors);

        Map<String, float[]> read = SafetensorsTensorReader.readAllFloats(file);
        assertArrayEquals(new float[]{1f, 2f, 3f, 4f}, read.get("a"), 1e-6f);
        assertArrayEquals(new float[]{1f, 2f, -1f}, read.get("b"), 1e-3f);
        assertArrayEquals(new float[]{1f, -2.5f}, read.get("c"), 1e-3f);
    }

    @Test
    void unsupportedDtypeFailsTypedWithTheDtypeNamed() throws IOException {
        Map<String, SafetensorsFixture.Tensor> tensors =
                new LinkedHashMap<String, SafetensorsFixture.Tensor>();
        tensors.put("bad", new SafetensorsFixture.Tensor("I64", new long[]{1},
                new byte[]{1, 0, 0, 0, 0, 0, 0, 0}));
        Path file = temp.resolve("model.safetensors");
        SafetensorsFixture.write(file, tensors);

        IOException failure = assertThrows(IOException.class,
                () -> SafetensorsTensorReader.readAllFloats(file));
        assertTrue(failure.getMessage().contains("I64"),
                "the failure must name the unsupported dtype: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("supported"),
                "the failure must point at the supported set: " + failure.getMessage());
    }
}
