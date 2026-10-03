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

    static Path castEmbeddingModel() throws Exception {        ByteArrayOutputStream graph = new ByteArrayOutputStream();
        writeFieldMessage(graph, 1, castNode());
        writeFieldString(graph, 2, "cortexj-cast-graph");
        writeFieldMessage(graph, 11, tensorValueInfo("input_ids", 7L, true));
        writeFieldMessage(graph, 12, tensorValueInfo("embeddings", 1L, false));
        ByteArrayOutputStream model = new ByteArrayOutputStream();
        writeFieldVarint(model, 1, 8L);
        writeFieldMessage(model, 7, graph.toByteArray());
        writeFieldMessage(model, 8, onnxDomainOpset());
        Path file = Files.createTempFile("cortexj-cast", ".onnx");
        Files.write(file, model.toByteArray());
        return file;
    }

    private static byte[] castNode() {
        ByteArrayOutputStream attribute = new ByteArrayOutputStream();
        writeFieldString(attribute, 1, "to");
        writeFieldVarint(attribute, 3, 1L);
        writeFieldVarint(attribute, 20, 2L);
        ByteArrayOutputStream node = new ByteArrayOutputStream();
        writeFieldString(node, 1, "input_ids");
        writeFieldString(node, 2, "embeddings");
        writeFieldString(node, 4, "Cast");
        writeFieldMessage(node, 5, attribute.toByteArray());
        return node.toByteArray();
    }

    private static byte[] tensorValueInfo(String name, long elemType, boolean fixedBatchDim) {
        ByteArrayOutputStream shape = new ByteArrayOutputStream();
        writeFieldMessage(shape, 1,
                fixedBatchDim ? dimensionValue(1L) : dimensionParam("batch"));
        writeFieldMessage(shape, 1, dimensionParam("seq"));
        ByteArrayOutputStream tensor = new ByteArrayOutputStream();
        writeFieldVarint(tensor, 1, elemType);
        writeFieldMessage(tensor, 2, shape.toByteArray());
        ByteArrayOutputStream type = new ByteArrayOutputStream();
        writeFieldMessage(type, 1, tensor.toByteArray());
        ByteArrayOutputStream valueInfo = new ByteArrayOutputStream();
        writeFieldString(valueInfo, 1, name);
        writeFieldMessage(valueInfo, 2, type.toByteArray());
        return valueInfo.toByteArray();
    }

    private static byte[] dimensionValue(long value) {
        ByteArrayOutputStream dimension = new ByteArrayOutputStream();
        writeFieldVarint(dimension, 1, value);
        return dimension.toByteArray();
    }

    private static byte[] dimensionParam(String param) {
        ByteArrayOutputStream dimension = new ByteArrayOutputStream();
        writeFieldString(dimension, 2, param);
        return dimension.toByteArray();
    }

    private static byte[] onnxDomainOpset() {
        ByteArrayOutputStream opset = new ByteArrayOutputStream();
        writeFieldVarint(opset, 2, 17L);
        return opset.toByteArray();
    }

    private static void writeFieldMessage(ByteArrayOutputStream out, int field, byte[] payload) {
        writeTag(out, field, 2);
        writeVarint(out, payload.length);
        out.write(payload, 0, payload.length);
    }

    private static void writeFieldVarint(ByteArrayOutputStream out, int field, long value) {
        writeTag(out, field, 0);
        writeVarint(out, value);
    }

    private static void writeFieldString(ByteArrayOutputStream out, int field, String value) {
        byte[] payload = value.getBytes(StandardCharsets.UTF_8);
        writeTag(out, field, 2);
        writeVarint(out, payload.length);
        out.write(payload, 0, payload.length);
    }

    private static void writeTag(ByteArrayOutputStream out, int field, int wireType) {
        writeVarint(out, tag(field, wireType));
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
