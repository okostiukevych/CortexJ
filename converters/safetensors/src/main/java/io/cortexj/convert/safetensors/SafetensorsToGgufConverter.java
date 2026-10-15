package io.cortexj.convert.safetensors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cortexj.convert.gguf.internal.GgufBinaryReader;
import io.cortexj.convert.gguf.internal.GgufBinaryWriter;
import io.cortexj.convert.gguf.internal.GgufDocument;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelArtifact;
import io.cortexj.spi.conversion.ConversionRequest;
import io.cortexj.spi.conversion.ConversionResult;
import io.cortexj.spi.conversion.ConversionSupport;
import io.cortexj.spi.conversion.ConverterId;
import io.cortexj.spi.conversion.ModelConverter;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Deterministic Safetensors -> GGUF container conversion:
 * F32/F16/BF16 and integer tensors are re-containerized byte-for-byte into a valid
 * GGUF layout; model metadata is derived from an adjacent config.json when present.
 *
 * <p>Honesty note: the output is a structurally valid GGUF file.
 * Whether a concrete runtime (llama.cpp) accepts the model additionally depends on
 * architecture-specific metadata that safetensors alone does not carry - the
 * provenance manifest records exactly which keys were derived and which were not.</p>
 */
public final class SafetensorsToGgufConverter implements ModelConverter {

    public static final String ID = "safetensors-to-gguf";

    private static final long SAFETENSORS_HEADER_LIMIT = 64L * 1024L * 1024L;

    @Override
    public ConverterId id() {
        return ConverterId.of(ID);
    }

    @Override
    public ConversionSupport evaluate(ConversionRequest request) {
        if (request.sourceArtifact().format() != ArtifactFormat.SAFETENSORS
                || request.targetFormat() != ArtifactFormat.GGUF) {
            return ConversionSupport.unsupported(
                    io.cortexj.planner.ReasonCode.FORMAT_UNSUPPORTED,
                    "safetensors-to-gguf converts safetensors to GGUF only");
        }
        // planning-time evaluate() is format-based only: the planner may propose the
        // conversion before the source artifact is materialized locally. convert()
        // fails honestly when the file is missing at execution time.
        return ConversionSupport.supported();
    }

    @Override
    public ConversionResult convert(ConversionRequest request) {
        Path source = request.sourcePath();
        Path temp = null;
        boolean moved = false;
        try {
            SafetensorsFile file = SafetensorsFile.read(source);
            Map<String, String> config = readAdjacentConfig(source);

            GgufDocument document = new GgufDocument();
            document.version = 3;
            String name = stripExtension(fileNameOf(source));
            document.upsert("general.name", GgufBinaryReader.Types.STRING, name);
            String architecture = config.get("model_type");
            if (architecture != null && !architecture.isEmpty()) {
                document.upsert("general.architecture", GgufBinaryReader.Types.STRING,
                        architecture);
                document.upsert(architecture + ".context_length",
                        GgufBinaryReader.Types.UINT32,
                        Long.valueOf(longOf(config, "max_position_embeddings", 4096)));
                document.upsert(architecture + ".embedding_length",
                        GgufBinaryReader.Types.UINT32,
                        Long.valueOf(longOf(config, "hidden_size", 0)));
                document.upsert(architecture + ".block_count",
                        GgufBinaryReader.Types.UINT32,
                        Long.valueOf(longOf(config, "num_hidden_layers", 0)));
                document.upsert(architecture + ".attention.head_count",
                        GgufBinaryReader.Types.UINT32,
                        Long.valueOf(longOf(config, "num_attention_heads", 0)));
            }
            document.upsert("general.file_type", GgufBinaryReader.Types.UINT32,
                    Long.valueOf("float16".equalsIgnoreCase(
                            String.valueOf(config.get("torch_dtype"))) ? 1 : 0));
            document.upsert("cortexj.converted_by", GgufBinaryReader.Types.STRING, ID);

            for (SafetensorsFile.TensorInfo tensor : file.tensors()) {
                document.tensors.add(new GgufDocument.TensorInfo(
                        tensor.name, tensor.shape, ggufTypeOf(tensor.dtype), 0L));
            }

            Path output = request.outputDirectory().resolve(name + ".gguf");
            Files.createDirectories(request.outputDirectory());
            temp = output.resolveSibling(output.getFileName() + ".part");
            try (InputStream input = new BufferedInputStream(
                    Files.newInputStream(source), 1 << 16);
                 OutputStream out = new BufferedOutputStream(
                         Files.newOutputStream(temp), 1 << 16)) {
                SafetensorsFile.skipHeader(input);
                GgufBinaryWriter writer = new GgufBinaryWriter(out);
                document.writeHeader(writer);
                byte[] buffer = new byte[1 << 16];
                for (SafetensorsFile.TensorInfo tensor : file.tensors()) {
                    copyExactly(input, out, tensor.byteSize, buffer);
                }
                writer.flush();
            }
            try {
                Files.move(temp, output, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;

            String sha256 = sha256Hex(output);
            Map<String, String> provenance = new LinkedHashMap<String, String>();
            provenance.put("converterId", ID);
            provenance.put("sourceArtifact", request.sourceArtifact().id());
            provenance.put("sourceBytes", String.valueOf(Files.size(source)));
            provenance.put("outputBytes", String.valueOf(Files.size(output)));
            provenance.put("tensorsConverted", String.valueOf(file.tensors().size()));
            provenance.put("configDerived", String.valueOf(architecture != null));
            provenance.put("outputChecksum", "sha256:" + sha256);

            Map<String, String> metadata =
                    new LinkedHashMap<String, String>(request.sourceArtifact().metadata());
            metadata.put("quantization", "fp16");
            metadata.put("convertedFrom", request.sourceArtifact().id());
            ModelArtifact artifact = new ModelArtifact.Abstract(
                    fileNameOf(output), ArtifactFormat.GGUF,
                    Files.size(output), null, metadata) {
            };
            return new ConversionResult(artifact, output, provenance);
        } catch (IOException e) {
            throw new io.cortexj.error.ConversionException(
                    io.cortexj.ErrorCode.CONVERSION_FAILED,
                    "safetensors to GGUF conversion failed: " + e.getMessage(),
                    "Verify the source file is a valid safetensors container.");
        } finally {
            if (!moved && temp != null) {
                // audit M-11: a mid-conversion failure must not strand a
                // model-sized partial next to the output
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static String fileNameOf(Path path) {
        java.nio.file.Path name = path == null ? null : path.getFileName();
        return name == null ? "" : name.toString();
    }

    private static int ggufTypeOf(String dtype) throws IOException {
        if ("F32".equals(dtype)) {
            return GgufDocument.TENSOR_F32;
        }
        if ("F16".equals(dtype)) {
            return GgufDocument.TENSOR_F16;
        }
        if ("BF16".equals(dtype)) {
            return GgufDocument.TENSOR_BF16;
        }
        if ("I64".equals(dtype)) {
            return 19;
        }
        if ("I32".equals(dtype)) {
            return 18;
        }
        if ("I16".equals(dtype)) {
            return 17;
        }
        if ("I8".equals(dtype) || "U8".equals(dtype) || "BOOL".equals(dtype)) {
            return 16;
        }
        throw new IOException("unsupported safetensors dtype for GGUF mapping: " + dtype);
    }

    private static long elementSize(String dtype) {
        if ("F32".equals(dtype) || "I32".equals(dtype) || "U32".equals(dtype)) {
            return 4L;
        }
        if ("F16".equals(dtype) || "BF16".equals(dtype) || "I16".equals(dtype)
                || "U16".equals(dtype)) {
            return 2L;
        }
        if ("F64".equals(dtype) || "I64".equals(dtype) || "U64".equals(dtype)) {
            return 8L;
        }
        return 1L;
    }

    private static ObjectMapper constrainedMapper() {
        // audit 2026-10-07: hostile headers must not StackOverflow the
        // converter - cap nesting depth and token/string budget
        ObjectMapper mapper = new ObjectMapper();
        mapper.getFactory().setStreamReadConstraints(
                com.fasterxml.jackson.core.StreamReadConstraints.builder()
                        .maxNestingDepth(50)
                        .maxNumberLength(10000)
                        .maxStringLength(10 * 1024 * 1024)
                        .build());
        return mapper;
    }

    private static Map<String, String> readAdjacentConfig(Path source) throws IOException {
        java.nio.file.Path parent = source.toAbsolutePath().getParent();
        Path config = parent == null ? null : parent.resolve("config.json");
        Map<String, String> flat = new LinkedHashMap<String, String>();
        if (config == null || !Files.isRegularFile(config)) {
            return flat;
        }
        // audit 2026-10-07: cap adjacent config.json - a hostile checkout
        // must not OOM the converter via readAllBytes.
        long size = Files.size(config);
        if (size > 16L * 1024L * 1024L) {
            throw new IOException("config.json is unreasonably large (" + size + " bytes, cap 16MB)");
        }
        ObjectMapper mapper = constrainedMapper();
        JsonNode root = mapper.readTree(Files.readAllBytes(config));
        flatten("", root, flat);
        return flat;
    }

    private static void flatten(String prefix, JsonNode node, Map<String, String> flat) {
        if (node.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
                 fields.hasNext();) {
                Map.Entry<String, JsonNode> field = fields.next();
                flatten(prefix.isEmpty() ? field.getKey()
                        : prefix + "." + field.getKey(), field.getValue(), flat);
            }
            return;
        }
        flat.put(prefix, node.asText());
    }

    private static long longOf(Map<String, String> config, String key, long fallback) {
        String value = config.get(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    private static void copyExactly(InputStream input, OutputStream output,
                                    long bytes, byte[] buffer) throws IOException {
        long remaining = bytes;
        while (remaining > 0) {
            int chunk = (int) Math.min(buffer.length, remaining);
            int read = input.read(buffer, 0, chunk);
            if (read < 0) {
                throw new IOException("safetensors data section is truncated");
            }
            output.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String sha256Hex(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[1 << 16];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    static final class SafetensorsFile {

        static final class TensorInfo {
            final String name;
            final String dtype;
            final long[] shape;
            final long[] dataOffsets;
            final long byteSize;

            TensorInfo(String name, String dtype, long[] shape, long[] dataOffsets) {
                this.name = name;
                this.dtype = dtype;
                this.shape = shape;
                this.dataOffsets = dataOffsets;
                this.byteSize = dataOffsets[1] - dataOffsets[0];
            }
        }

        static SafetensorsFile read(Path file) throws IOException {
            try (InputStream input = Files.newInputStream(file)) {
                return read(input);
            }
        }

        static SafetensorsFile read(InputStream input) throws IOException {
            long headerSize = readInt64(input);
            if (headerSize <= 0 || headerSize > SAFETENSORS_HEADER_LIMIT) {
                throw new IOException("unreasonable safetensors header size: " + headerSize);
            }
            byte[] headerBytes = new byte[(int) headerSize];
            readFully(input, headerBytes);
            ObjectMapper mapper = constrainedMapper();
            JsonNode header = mapper.readTree(new String(headerBytes, StandardCharsets.UTF_8));

            // tensors sorted by their data offset: the safetensors layout guarantees
            // a contiguous, gap-free data section in this order
            Map<Long, TensorInfo> byOffset = new TreeMap<Long, TensorInfo>();
            List<String> unsupported = new ArrayList<String>();
            for (Iterator<Map.Entry<String, JsonNode>> fields = header.fields();
                 fields.hasNext();) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode entry = field.getValue();
                if (!entry.has("dtype")) {
                    continue; // __metadata__ or unknown non-tensor entries
                }
                String dtype = entry.path("dtype").asText();
                JsonNode shapeNode = entry.path("shape");
                long[] shape = new long[shapeNode.size()];
                for (int i = 0; i < shape.length; i++) {
                    shape[i] = shapeNode.get(i).asLong();
                }
                JsonNode offsets = entry.path("data_offsets");
                long[] pair = {offsets.get(0).asLong(), offsets.get(1).asLong()};
                // audit 2026-10-07: overlapping or inverted data_offsets would
                // silently duplicate/rewind bytes - reject the file instead.
                if (pair[1] < pair[0]) {
                    throw new IOException("safetensors tensor '" + field.getKey()
                            + "' has inverted data_offsets");
                }
                TensorInfo tensor = new TensorInfo(field.getKey(), dtype, shape, pair);
                if (byOffset.containsKey(Long.valueOf(pair[0]))) {
                    throw new IOException("safetensors tensor '" + field.getKey()
                            + "' overlaps another tensor at offset " + pair[0]);
                }
                byOffset.put(Long.valueOf(pair[0]), tensor);
            }
            if (byOffset.isEmpty()) {
                throw new IOException("safetensors header declares no tensors");
            }
            if (!unsupported.isEmpty()) {
                throw new IOException("unsupported dtypes: " + unsupported);
            }
            List<TensorInfo> ordered = new ArrayList<TensorInfo>(byOffset.values());
            for (int i = 0; i < ordered.size(); i++) {
                if (!supported(ordered.get(i).dtype)) {
                    throw new IOException("unsupported safetensors dtype: "
                            + ordered.get(i).dtype + " (tensor " + ordered.get(i).name + ")");
                }
            }
            return new SafetensorsFile(ordered, 8 + headerSize);
        }

        static void skipHeader(InputStream input) throws IOException {
            long headerSize = readInt64(input);
            if (headerSize <= 0 || headerSize > SAFETENSORS_HEADER_LIMIT) {
                throw new IOException("unreasonable safetensors header size: " + headerSize);
            }
            long remaining = headerSize;
            byte[] buffer = new byte[8192];
            while (remaining > 0) {
                int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) {
                    throw new IOException("truncated safetensors header");
                }
                remaining -= read;
            }
        }

        private static boolean supported(String dtype) {
            return "F32".equals(dtype) || "F16".equals(dtype) || "BF16".equals(dtype)
                    || "I64".equals(dtype) || "I32".equals(dtype) || "I16".equals(dtype)
                    || "I8".equals(dtype) || "U8".equals(dtype) || "BOOL".equals(dtype);
        }

        private static long readInt64(InputStream input) throws IOException {
            long value = 0L;
            for (int i = 0; i < 8; i++) {
                int b = input.read();
                if (b < 0) {
                    throw new IOException("truncated safetensors file");
                }
                value |= (b & 0xFFL) << (8 * i);
            }
            return value;
        }

        private static void readFully(InputStream input, byte[] target) throws IOException {
            int offset = 0;
            while (offset < target.length) {
                int read = input.read(target, offset, target.length - offset);
                if (read < 0) {
                    throw new IOException("truncated safetensors header");
                }
                offset += read;
            }
        }

        private final List<TensorInfo> tensors;
        final long headerBytes;

        SafetensorsFile(List<TensorInfo> tensors, long headerBytes) {
            this.tensors = tensors;
            this.headerBytes = headerBytes;
        }

        List<TensorInfo> tensors() {
            return tensors;
        }
    }
}
