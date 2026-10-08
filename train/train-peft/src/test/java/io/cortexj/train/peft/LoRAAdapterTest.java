package io.cortexj.train.peft;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoRAAdapterTest {

    private static LoRAAdapter adapter() {
        LoRAConfig config = new LoRAConfig(2, 4, 0.05,
                java.util.Arrays.asList("q_proj", "v_proj"));
        float[][] a = {{0.5f, -0.25f}, {1.0f, 0.0f}};
        float[][] b = {{1.0f, 0.0f}, {0.25f, 0.5f}, {0.0f, 2.0f}};
        return new LoRAAdapter("q_proj", 2, 3, config, a, b);
    }

    @Test
    void configKeepsValuesAndValidates() {
        LoRAConfig config = new LoRAConfig(8, 16, 0.05,
                java.util.Arrays.asList("q_proj"));
        assertEquals(8, config.rank());
        assertEquals(16, config.alpha());
        assertEquals(0.05, config.dropout(), 1e-12);
        assertEquals(java.util.Arrays.asList("q_proj"), config.targetModules());
        assertThrows(IllegalArgumentException.class,
                () -> new LoRAConfig(0, 16, 0.0, java.util.Arrays.asList("q")));
        assertThrows(IllegalArgumentException.class,
                () -> new LoRAConfig(8, 16, -0.1, java.util.Arrays.asList("q")));
        assertThrows(IllegalArgumentException.class,
                () -> new LoRAConfig(8, 16, 0.0, java.util.Collections.<String>emptyList()));
    }

    @Test
    void mergeAppliesScaledLowRankUpdate() {
        LoRAAdapter lora = adapter();
        // W is 3x2 (out x in), update = (alpha/rank) * B * A is 3x2
        float[][] base = {{1.0f, 2.0f}, {3.0f, 4.0f}, {5.0f, 6.0f}};
        float[][] merged = lora.merge(base);
        // (alpha/rank) = 4/2 = 2.0
        // B*A row0 = [1*0.5 + 0*1, 1*(-0.25) + 0*0] = [0.5, -0.25]
        // B*A row1 = [0.25*0.5 + 0.5*1, 0.25*(-0.25) + 0.5*0] = [0.625, -0.0625]
        // B*A row2 = [0*0.5 + 2*1, 0*(-0.25) + 2*0] = [2.0, 0.0]
        assertEquals(3, merged.length);
        assertEquals(2, merged[0].length);
        assertEquals(1.0f + 2.0f * 0.5f, merged[0][0], 1e-6);
        assertEquals(2.0f + 2.0f * (-0.25f), merged[0][1], 1e-6);
        assertEquals(3.0f + 2.0f * 0.625f, merged[1][0], 1e-6);
        assertEquals(4.0f + 2.0f * (-0.0625f), merged[1][1], 1e-6);
        assertEquals(5.0f + 2.0f * 2.0f, merged[2][0], 1e-6);
        assertEquals(6.0f + 2.0f * 0.0f, merged[2][1], 1e-6);
        // base weights stay untouched
        assertEquals(1.0f, base[0][0], 1e-12);
    }

    @Test
    void mergeRejectsShapeMismatch() {
        LoRAAdapter lora = adapter();
        float[][] wrong = {{1.0f, 2.0f}};
        assertThrows(IllegalArgumentException.class, () -> lora.merge(wrong));
    }

    private static float[] flatten(float[][] matrix) {
        int size = 0;
        for (float[] row : matrix) {
            size += row.length;
        }
        float[] flat = new float[size];
        int at = 0;
        for (float[] row : matrix) {
            System.arraycopy(row, 0, flat, at, row.length);
            at += row.length;
        }
        return flat;
    }

    @Test
    void safetensorsRoundTrip() throws Exception {
        LoRAAdapter lora = adapter();
        Path file = Files.createTempFile("adapter", ".safetensors");
        try {
            LoRASafetensors.write(lora, file, java.util.Map.of("note", "round-trip"));
            LoRAAdapter read = LoRASafetensors.read(file);
            assertEquals("q_proj", read.moduleName());
            assertEquals(2, read.inputSize());
            assertEquals(3, read.outputSize());
            assertEquals(lora.config().rank(), read.config().rank());
            assertEquals(lora.config().alpha(), read.config().alpha());
            assertArrayEquals(flatten(lora.matrixA()), flatten(read.matrixA()), 1e-6f);
            assertArrayEquals(flatten(lora.matrixB()), flatten(read.matrixB()), 1e-6f);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void writtenFileHasSidecarAndChecksummableContent() throws Exception {
        LoRAAdapter lora = adapter();
        Path file = Files.createTempFile("adapter", ".safetensors");
        Path sidecar = Files.createTempFile("adapter_config", ".json");
        try {
            LoRASafetensors.write(lora, file, java.util.Map.of("note", "x"));
            assertTrue(Files.size(file) > 8);
            // header is JSON with the standard tensor names
            byte[] all = Files.readAllBytes(file);
            long headerLength = 0;
            for (int i = 0; i < 8; i++) {
                headerLength |= (long) (all[i] & 0xFF) << (8 * i);
            }
            String header = new String(all, 8, (int) headerLength,
                    java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(header.contains("adapter.A"), header);
            assertTrue(header.contains("adapter.B"), header);
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(sidecar);
        }
    }
}
