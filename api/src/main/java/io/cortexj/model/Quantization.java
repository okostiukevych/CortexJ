package io.cortexj.model;

import java.util.Locale;

public final class Quantization implements Comparable<Quantization> {

    private static final Quantization FP32 = new Quantization("fp32", 32);
    private static final Quantization BF16 = new Quantization("bf16", 16);
    private static final Quantization FP16 = new Quantization("fp16", 16);
    private static final Quantization I8 = new Quantization("int8", 8);

    private final String label;
    private final int bitsPerWeight;

    private Quantization(String label, int bitsPerWeight) {
        this.label = label;
        this.bitsPerWeight = bitsPerWeight;
    }

    public static Quantization parse(String rawLabel) {
        if (rawLabel == null) {
            return FP32;
        }
        String label = rawLabel.trim().toLowerCase(Locale.ROOT).replace(".gguf", "").replace("-", "").replace("_", "");
        if (label.isEmpty()) {
            return FP32;
        }
        if (label.equals("fp32") || label.equals("f32") || label.equals("float32")) {
            return FP32;
        }
        if (label.equals("bf16") || label.equals("bfloat16")) {
            return BF16;
        }
        if (label.equals("fp16") || label.equals("f16") || label.equals("float16") || label.equals("half")) {
            return FP16;
        }
        if (label.equals("int8") || label.equals("q8")) {
            return I8;
        }
        if (label.startsWith("q") && label.length() >= 2) {
            try {
                int bits = Integer.parseInt(label.substring(1, 2));
                if (bits > 0 && bits <= 8) {
                    return new Quantization(normalize(rawLabel), bits);
                }
            } catch (NumberFormatException ignored) {
                return new Quantization(normalize(rawLabel), 8);
            }
        }
        if (label.endsWith("bit")) {
            try {
                int bits = Integer.parseInt(label.replace("bit", ""));
                if (bits > 0 && bits <= 32) {
                    return new Quantization(normalize(rawLabel), bits);
                }
            } catch (NumberFormatException ignored) {
                return new Quantization(normalize(rawLabel), 8);
            }
        }
        return new Quantization(normalize(rawLabel), 16);
    }

    private static String normalize(String rawLabel) {
        return rawLabel.trim().toLowerCase(Locale.ROOT).replace(".gguf", "");
    }

    public String label() {
        return label;
    }

    public int bitsPerWeight() {
        return bitsPerWeight;
    }

    @Override
    public int compareTo(Quantization other) {
        return Integer.compare(bitsPerWeight, other.bitsPerWeight);
    }

    @Override
    public String toString() {
        return label;
    }
}
