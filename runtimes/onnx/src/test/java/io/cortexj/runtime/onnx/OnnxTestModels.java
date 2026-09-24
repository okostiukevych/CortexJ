package io.cortexj.runtime.onnx;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class OnnxTestModels {

    static Path minimalOnnxModel() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        writeFieldString(bytes, 2, "onnx-test-producer");
        writeFieldVarint(bytes, 1, 8);
        writeFieldString(bytes, 4, "cortexj.test");
        Path file = Files.createTempFile("cortexj-tck", ".onnx");
        Files.write(file, bytes.toByteArray());
        return file;
    }

    private static void writeFieldVarint(ByteArrayOutputStream out, int field, long value) {
        out.write(tag(field, 0));
        writeVarint(out, value);
    }

    private static void writeFieldString(ByteArrayOutputStream out, int field, String value) {
        byte[] payload = value.getBytes(StandardCharsets.UTF_8);
        out.write(tag(field, 2));
        writeVarint(out, payload.length);
        out.write(payload, 0, payload.length);
    }

    private static int tag(int field, int wireType) {
        return (field << 3) | wireType;
    }

    private static void writeVarint(ByteArrayOutputStream out, long value) {
        long remaining = value;
        while (true) {
            int byte7 = (int) (remaining & 0x7F);
            remaining >>>= 7;
            if (remaining == 0) {
                out.write(byte7);
                break;
            }
            out.write(byte7 | 0x80);
        }
    }

    private OnnxTestModels() {
    }
}
