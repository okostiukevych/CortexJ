package io.cortexj.convert.lora;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dequantization hand-computed per block layout (ADR-029): exact
 * values, not self-consistency.
 */
class GgufDequantTest {

    @Test
    void q80BlockDequantizesExactly() throws Exception {
        // scale 0.5 (fp16 0x3800), 32 int8 values: first 2, rest 0
        byte[] block = new byte[34];
        block[0] = 0x00;
        block[1] = 0x38;
        block[2] = 2;
        float[] values = GgufDequant.dequantize(GgufDequant.TENSOR_Q8_0, block, 32);
        assertEquals(32, values.length);
        assertEquals(1.0f, values[0], 1e-6);
        for (int i = 1; i < 32; i++) {
            assertEquals(0.0f, values[i], 1e-6);
        }
    }

    @Test
    void q40BlockDequantizesExactly() throws Exception {
        // scale 0.25 (fp16 0x3400), byte 0x7F: low nibble 15, high nibble 7
        byte[] block = new byte[18];
        block[0] = 0x00;
        block[1] = 0x34;
        block[2] = (byte) 0x7F;
        float[] values = GgufDequant.dequantize(GgufDequant.TENSOR_Q4_0, block, 32);
        assertEquals((15 - 8) * 0.25f, values[0], 1e-6);
        assertEquals((7 - 8) * 0.25f, values[1], 1e-6);
    }

    @Test
    void q41UsesMinInsteadOfOffset() throws Exception {
        // d=0.5 (0x3800), m=0.25 (0x3400), nibbles 2,0
        byte[] block = new byte[20];
        block[0] = 0x00;
        block[1] = 0x38;
        block[2] = 0x00;
        block[3] = 0x34;
        block[4] = 0x02;
        float[] values = GgufDequant.dequantize(GgufDequant.TENSOR_Q4_1, block, 32);
        assertEquals(2 * 0.5f + 0.25f, values[0], 1e-6);
        assertEquals(0.25f, values[1], 1e-6);
    }

    @Test
    void q50HighBitExtendsNibble() throws Exception {
        // d=1.0 (0x3C00), qh all zero, nibbles 15,0
        byte[] block = new byte[22];
        block[0] = 0x00;
        block[1] = 0x3C;
        block[6] = (byte) 0x0F;
        float[] values = GgufDequant.dequantize(GgufDequant.TENSOR_Q5_0, block, 32);
        assertEquals(15.0f - 16.0f, values[0], 1e-6);
        assertEquals(0.0f - 16.0f, values[1], 1e-6);
    }

    @Test
    void f16RoundTrips() throws Exception {
        byte[] data = {(byte) 0x00, (byte) 0x3C, (byte) 0x00, (byte) 0xBC};
        float[] values = GgufDequant.dequantize(GgufDequant.TENSOR_F16, data, 2);
        assertEquals(1.0f, values[0], 1e-6);
        assertEquals(-1.0f, values[1], 1e-6);
    }

    @Test
    void kQuantsFailTyped() {
        byte[] data = new byte[144];
        java.io.IOException failure = assertThrows(java.io.IOException.class,
                () -> GgufDequant.dequantize(12, data, 256));
        assertTrue(failure.getMessage().contains("Q4_K") || failure.getMessage().contains("12"),
                failure.getMessage());
        assertTrue(!GgufDequant.supported(12));
        assertTrue(GgufDequant.supported(GgufDequant.TENSOR_Q8_0));
    }

    @Test
    void quantRoundTripStaysBounded() throws Exception {
        float[] values = new float[64];
        for (int i = 0; i < values.length; i++) {
            values[i] = (i - 32) * 0.05f;
        }
        float[] q8 = GgufDequant.dequantize(GgufDequant.TENSOR_Q8_0,
                GgufRequant.requantize(values, GgufDequant.TENSOR_Q8_0), 64);
        float[] q4 = GgufDequant.dequantize(GgufDequant.TENSOR_Q4_0,
                GgufRequant.requantize(values, GgufDequant.TENSOR_Q4_0), 64);
        for (int i = 0; i < values.length; i++) {
            assertTrue(Math.abs(q8[i] - values[i]) < 0.02, "q8 at " + i);
            assertTrue(Math.abs(q4[i] - values[i]) < 0.3, "q4 at " + i);
        }
    }

    @Test
    void kQuantRequantFailsTyped() {
        assertThrows(java.io.IOException.class,
                () -> GgufRequant.requantize(new float[]{1.0f}, 12));
    }
}
