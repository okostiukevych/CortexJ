package io.cortexj.train.peft;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Interoperability contract: the container is standard safetensors, but
 * foreign adapter naming (HuggingFace PEFT style) fails typed with a
 * clear message, never silent misinterpretation.
 */
class LoRASafetensorsInteropTest {

    @Test
    void foreignNamingFailsTyped() throws Exception {
        Path foreign = Files.createTempFile("foreign-adapter", ".safetensors");
        try {
            writeCanonical(foreign,
                    "base_model.model.q_proj.lora_A.weight",
                    "base_model.model.q_proj.lora_B.weight");
            IOException failure = assertThrows(IOException.class,
                    () -> LoRASafetensors.read(foreign));
            assertTrue(failure.getMessage().contains("adapter tensors"),
                    failure.getMessage());
        } finally {
            Files.deleteIfExists(foreign);
        }
    }

    @Test
    void corruptedContainerFailsTyped() throws Exception {
        Path corrupt = Files.createTempFile("corrupt-adapter", ".safetensors");
        try {
            Files.write(corrupt, new byte[]{1, 2, 3});
            assertThrows(IOException.class, () -> LoRASafetensors.read(corrupt));
        } finally {
            Files.deleteIfExists(corrupt);
        }
    }

    @Test
    void offsetsBeyondFileSizeFailTyped() throws Exception {
        Path lying = Files.createTempFile("lying-adapter", ".safetensors");
        try {
            // canonical header claiming a huge tensor that is not there
            String header = "{\"" + LoRASafetensors.TENSOR_A
                    + "\":{\"dtype\":\"F32\",\"shape\":[999999,999],"
                    + "\"data_offsets\":[0,3888887116]}";
            byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);
            try (var out = Files.newOutputStream(lying)) {
                for (int i = 0; i < 8; i++) {
                    out.write((byte) ((headerBytes.length >>> (8 * i)) & 0xFF));
                }
                out.write(headerBytes);
            }
            assertThrows(Exception.class, () -> LoRASafetensors.read(lying));
        } finally {
            Files.deleteIfExists(lying);
        }
    }

    /**
     * Writes a canonical safetensors container (what a foreign producer
     * would emit) with the given tensor names and a 2x2/3x2 shape.
     */
    private static void writeCanonical(Path file, String nameA, String nameB) throws Exception {
        String header = "{"
                + "\"" + nameA + "\":{\"dtype\":\"F32\",\"shape\":[2,2],\"data_offsets\":[0,16]},"
                + "\"" + nameB + "\":{\"dtype\":\"F32\",\"shape\":[3,2],\"data_offsets\":[16,40]}"
                + "}";
        byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);
        int padding = (8 - (headerBytes.length % 8)) % 8;
        try (var out = Files.newOutputStream(file)) {
            long length = headerBytes.length + padding;
            for (int i = 0; i < 8; i++) {
                out.write((byte) ((length >>> (8 * i)) & 0xFF));
            }
            out.write(headerBytes);
            for (int i = 0; i < padding; i++) {
                out.write(' ');
            }
            // A is 2x2 (16 bytes), B is 3x2 (24 bytes): 40 data bytes
            for (int i = 0; i < 5; i++) {
                for (int c = 0; c < 2; c++) {
                    int bits = Float.floatToIntBits(i + c * 0.5f);
                    out.write(bits & 0xFF);
                    out.write((bits >>> 8) & 0xFF);
                    out.write((bits >>> 16) & 0xFF);
                    out.write((bits >>> 24) & 0xFF);
                }
            }
        }
        assertEquals(8 + headerBytes.length + padding + 40, Files.size(file));
    }
}
