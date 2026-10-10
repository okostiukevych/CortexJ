package io.cortexj.convert.lora;

import io.cortexj.convert.gguf.internal.GgufBinaryWriter;
import io.cortexj.convert.gguf.internal.GgufDocument;
import io.cortexj.train.peft.LoRAAdapter;
import io.cortexj.train.peft.LoRASafetensors;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline GGUF-side LoRA merge (ADR-029): dequantize the mapped base
 * tensors, apply the adapter delta, requantize per policy, write a new
 * GGUF plus a conversion manifest. Originals are immutable; unmapped
 * tensors pass through byte-identical.
 */
public final class LoraMerge {

    public enum QuantPolicy {
        SAME,
        F32,
        F16,
        Q8_0,
        Q4_0
    }

    /**
     * One explicit mapping entry: which GGUF tensor receives which
     * adapter file. No guessing (ADR-029).
     */
    public static final class Mapping {

        private final String ggufTensor;
        private final Path adapterFile;

        public Mapping(String ggufTensor, Path adapterFile) {
            if (ggufTensor == null || ggufTensor.isEmpty() || adapterFile == null) {
                throw new IllegalArgumentException("tensor name and adapter file are required");
            }
            this.ggufTensor = ggufTensor;
            this.adapterFile = adapterFile;
        }

        public String ggufTensor() {
            return ggufTensor;
        }

        public Path adapterFile() {
            return adapterFile;
        }
    }

    public static final class Request {

        private final Path baseModel;
        private final List<Mapping> mappings;
        private final QuantPolicy quantPolicy;
        private final Path outputModel;

        public Request(Path baseModel, List<Mapping> mappings,
                       QuantPolicy quantPolicy, Path outputModel) {
            if (baseModel == null || mappings == null || mappings.isEmpty()
                    || quantPolicy == null || outputModel == null) {
                throw new IllegalArgumentException(
                        "base model, mappings, quant policy and output are required");
            }
            this.baseModel = baseModel;
            this.mappings = Collections.unmodifiableList(new ArrayList<>(mappings));
            this.quantPolicy = quantPolicy;
            this.outputModel = outputModel;
        }

        public Path baseModel() {
            return baseModel;
        }

        public List<Mapping> mappings() {
            return mappings;
        }

        public QuantPolicy quantPolicy() {
            return quantPolicy;
        }

        public Path outputModel() {
            return outputModel;
        }
    }

    public static final class Result {

        private final Path outputModel;
        private final Path manifest;
        private final List<String> mergedTensors;
        private final Map<String, String> manifestEntries;

        Result(Path outputModel, Path manifest, List<String> mergedTensors,
               Map<String, String> manifestEntries) {
            this.outputModel = outputModel;
            this.manifest = manifest;
            this.mergedTensors = Collections.unmodifiableList(mergedTensors);
            this.manifestEntries = Collections.unmodifiableMap(manifestEntries);
        }

        public Path outputModel() {
            return outputModel;
        }

        public Path manifest() {
            return manifest;
        }

        public List<String> mergedTensors() {
            return mergedTensors;
        }

        public Map<String, String> manifestEntries() {
            return manifestEntries;
        }
    }

    public static Result merge(Request request) throws IOException {
        GgufDocument base = GgufDocument.read(request.baseModel());
        long fileSize = Files.size(request.baseModel());
        Map<String, Long> trueSizes = trueSizes(base, fileSize);
        Map<String, Mapping> byTensor = new LinkedHashMap<>();
        for (Mapping mapping : request.mappings()) {
            if (byTensor.put(mapping.ggufTensor(), mapping) != null) {
                throw new IOException("duplicate mapping for tensor: " + mapping.ggufTensor());
            }
        }

        GgufDocument merged = new GgufDocument();
        merged.version = base.version;
        for (GgufDocument.KeyValue kv : base.metadata) {
            merged.metadata.add(new GgufDocument.KeyValue(kv.key, kv.type, kv.value));
        }
        List<String> mergedTensors = new ArrayList<>();
        Map<String, byte[]> newData = new LinkedHashMap<>();
        Map<String, String> manifest = new LinkedHashMap<>();
        manifest.put("converter", "cortexj-convert-lora/0.1.0");
        manifest.put("base.model", request.baseModel().toString());
        manifest.put("quant.policy", request.quantPolicy().name());

        try (var channel = java.nio.channels.FileChannel.open(
                request.baseModel(), java.nio.file.StandardOpenOption.READ)) {
            for (GgufDocument.TensorInfo tensor : base.tensors) {
                Mapping mapping = byTensor.get(tensor.name);
                if (mapping == null) {
                    // untouched: verbatim bytes, true on-disk size
                    byte[] raw = readBytes(channel,
                            base.dataStartOffset + tensor.offset, trueSizes.get(tensor.name));
                    merged.tensors.add(new GgufDocument.TensorInfo(tensor.name,
                            tensor.dimensions.clone(), tensor.type, 0,
                            trueSizes.get(tensor.name)));
                    newData.put(tensor.name, raw);
                    continue;
                }
                byte[] raw = readBytes(channel,
                        base.dataStartOffset + tensor.offset, trueSizes.get(tensor.name));
                MergedTensor produced = mergeTensor(tensor, raw, mapping, request.quantPolicy());
                merged.tensors.add(new GgufDocument.TensorInfo(tensor.name,
                        tensor.dimensions.clone(), produced.type, 0, produced.data.length));
                newData.put(tensor.name, produced.data);
                mergedTensors.add(tensor.name);
                manifest.put("tensor." + tensor.name,
                        GgufDequant.typeName(tensor.type) + "->"
                                + GgufDequant.typeName(produced.type));
            }
        }

        // adapter provenance: every adapter checksummed
        for (Mapping mapping : request.mappings()) {
            manifest.put("adapter." + mapping.ggufTensor(),
                    sha256Hex(Files.readAllBytes(mapping.adapterFile())));
        }

        merged.upsert("cortexj.lora.merged", GgufType.STRING, "true");
        merged.upsert("cortexj.lora.converter", GgufType.STRING, "cortexj-convert-lora/0.1.0");
        // the writer pads tensor data to 64: declare it so any reader
        // (including ours, defaulting to the 32 spec default) lands right
        merged.upsert("general.alignment", 4, 64);

        Path output = request.outputModel();
        try (OutputStream stream = Files.newOutputStream(output)) {
            GgufBinaryWriter writer = new GgufBinaryWriter(stream);
            merged.writeHeader(writer);
            for (GgufDocument.TensorInfo tensor : merged.tensors) {
                writer.write(newData.get(tensor.name));
            }
        }
        Path manifestPath = Path.of(output + ".manifest.properties");
        StringBuilder manifestBody = new StringBuilder();
        for (Map.Entry<String, String> entry : manifest.entrySet()) {
            manifestBody.append(entry.getKey()).append('=').append(entry.getValue())
                    .append('\n');
        }
        Files.writeString(manifestPath, manifestBody.toString(), StandardCharsets.UTF_8);
        return new Result(output, manifestPath, mergedTensors, manifest);
    }

    private static final class MergedTensor {

        final int type;
        final byte[] data;

        MergedTensor(int type, byte[] data) {
            this.type = type;
            this.data = data;
        }
    }

    private static MergedTensor mergeTensor(GgufDocument.TensorInfo tensor, byte[] raw,
                                            Mapping mapping, QuantPolicy policy)
            throws IOException {
        if (!GgufDequant.supported(tensor.type)) {
            throw new IOException("GGUF dequantization is not supported for "
                    + GgufDequant.typeName(tensor.type) + " in 0.x: " + tensor.name);
        }
        float[] base = GgufDequant.dequantize(tensor.type, raw, tensor.elementCount());
        LoRAAdapter adapter;
        try {
            adapter = LoRASafetensors.read(mapping.adapterFile());
        } catch (IOException e) {
            throw new IOException("adapter for " + tensor.name + " is unreadable: " + e, e);
        }
        long adapterElements = (long) adapter.outputSize() * adapter.inputSize();
        if (adapterElements != tensor.elementCount()) {
            throw new IOException("adapter " + adapter.moduleName() + " has " + adapterElements
                    + " elements but tensor " + tensor.name + " has " + tensor.elementCount());
        }
        double scale = adapter.config().scaling();
        float[][] a = adapter.matrixA();
        float[][] b = adapter.matrixB();
        float[] merged = new float[base.length];
        int inputs = adapter.inputSize();
        int outputs = adapter.outputSize();
        for (int o = 0; o < outputs; o++) {
            for (int i = 0; i < inputs; i++) {
                double delta = 0;
                for (int r = 0; r < adapter.config().rank(); r++) {
                    delta += b[o][r] * a[r][i];
                }
                merged[o * inputs + i] = (float) (base[o * inputs + i] + scale * delta);
            }
        }
        int targetType = targetType(tensor.type, policy);
        return new MergedTensor(targetType, GgufRequant.requantize(merged, targetType));
    }

    private static int targetType(int sourceType, QuantPolicy policy) throws IOException {
        switch (policy) {
            case F32:
                return GgufDequant.TENSOR_F32;
            case F16:
                return GgufDequant.TENSOR_F16;
            case Q8_0:
                return GgufDequant.TENSOR_Q8_0;
            case Q4_0:
                return GgufDequant.TENSOR_Q4_0;
            case SAME:
            default:
                if (sourceType == GgufDequant.TENSOR_F32
                        || sourceType == GgufDequant.TENSOR_F16
                        || sourceType == GgufDequant.TENSOR_Q8_0
                        || sourceType == GgufDequant.TENSOR_Q4_0) {
                    return sourceType;
                }
                throw new IOException("GGUF requantization to "
                        + GgufDequant.typeName(sourceType) + " is not supported in 0.x"
                        + " (use --quant f32, f16, q8_0 or q4_0)");
        }
    }

    private static Map<String, Long> trueSizes(GgufDocument document, long fileSize) {
        List<GgufDocument.TensorInfo> ordered = new ArrayList<>(document.tensors);
        ordered.sort((left, right) -> Long.compare(left.offset, right.offset));
        Map<String, Long> sizes = new LinkedHashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            long end = i + 1 < ordered.size()
                    ? ordered.get(i + 1).offset
                    : fileSize - document.dataStartOffset;
            sizes.put(ordered.get(i).name, end - ordered.get(i).offset);
        }
        return sizes;
    }

    private static byte[] readBytes(java.nio.channels.FileChannel channel, long position, long count)
            throws IOException {
        if (count < 0 || count > Integer.MAX_VALUE) {
            throw new IOException("tensor data size is unreasonable: " + count);
        }
        ByteBuffer buffer = ByteBuffer.allocate((int) count);
        channel.position(position);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                throw new IOException("truncated tensor data at " + position);
            }
        }
        return buffer.array();
    }

    private static String sha256Hex(byte[] bytes) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return "sha256:" + hex;
        } catch (Exception e) {
            throw new IOException("SHA-256 is required", e);
        }
    }

    private static final class GgufType {

        static final int STRING = 8;
    }
}
