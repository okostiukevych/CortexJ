package io.cortexj.convert.gguf;

import io.cortexj.convert.gguf.internal.Float16;
import io.cortexj.convert.gguf.internal.GgufBinaryReader;
import io.cortexj.convert.gguf.internal.GgufBinaryWriter;
import io.cortexj.convert.gguf.internal.GgufDocument;
import io.cortexj.spi.conversion.ConversionRequest;
import io.cortexj.spi.conversion.ConversionResult;
import io.cortexj.spi.conversion.ConversionSupport;
import io.cortexj.spi.conversion.ConverterId;
import io.cortexj.spi.conversion.ModelConverter;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelArtifact;

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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deterministic GGUF float-width converter (spec 31 Level C, 32):
 * F32 tensors -> F16 (MEMORY_FIRST) and F16 tensors -> F32, everything else
 * passed through byte-for-byte. Original artifacts are never modified.
 */
public final class GgufFloatConverter implements ModelConverter {

    public static final String ID = "gguf-float";

    private static final int GGUF_MAGIC = 0x46554747;

    public enum Direction {
        TO_F16("f16"),
        TO_F32("f32");

        public final String label;

        Direction(String label) {
            this.label = label;
        }
    }

    @Override
    public ConverterId id() {
        return ConverterId.of(ID);
    }

    @Override
    public ConversionSupport evaluate(ConversionRequest request) {
        if (request.sourceArtifact().format() != ArtifactFormat.GGUF
                || request.targetFormat() != ArtifactFormat.GGUF) {
            return ConversionSupport.unsupported(
                    io.cortexj.planner.ReasonCode.FORMAT_UNSUPPORTED,
                    "gguf-float converts GGUF to GGUF only");
        }
        String quantization = request.targetQuantization();
        if (quantization != null && !quantization.isEmpty()
                && !Direction.TO_F16.label.equals(quantization)
                && !Direction.TO_F32.label.equals(quantization)) {
            return ConversionSupport.unsupported(
                    io.cortexj.planner.ReasonCode.FORMAT_UNSUPPORTED,
                    "gguf-float only produces f16 or f32 targets, not '" + quantization + "'");
        }
        if (request.sourcePath() == null || !Files.isRegularFile(request.sourcePath())) {
            return ConversionSupport.unsupported(
                    io.cortexj.planner.ReasonCode.ARTIFACT_NOT_FOUND,
                    "conversion requires a local source file (pull the model first)");
        }
        return ConversionSupport.supported();
    }

    @Override
    public ConversionResult convert(ConversionRequest request) {
        Direction direction = Direction.TO_F32.label.equals(request.targetQuantization())
                ? Direction.TO_F32
                : Direction.TO_F16;
        Path source = request.sourcePath();
        try {
            GgufDocument document = GgufDocument.read(source);
            long f32Tensors = 0;
            long f16Tensors = 0;
            for (GgufDocument.TensorInfo tensor : document.tensors) {
                if (tensor.type == GgufDocument.TENSOR_F32) {
                    f32Tensors++;
                } else if (tensor.type == GgufDocument.TENSOR_F16) {
                    f16Tensors++;
                }
            }
            if (direction == Direction.TO_F16 && f32Tensors == 0) {
                throw new IOException("source contains no F32 tensors to convert");
            }
            if (direction == Direction.TO_F32 && f16Tensors == 0) {
                throw new IOException("source contains no F16 tensors to convert");
            }

            Path output = request.outputDirectory().resolve(outputName(source, direction));
            Files.createDirectories(request.outputDirectory());
            Path temp = output.resolveSibling(output.getFileName() + ".part");
            long converted = 0L;
            try (InputStream input = new BufferedInputStream(Files.newInputStream(source), 1 << 16);
                 OutputStream out = new BufferedOutputStream(Files.newOutputStream(temp), 1 << 16)) {
                GgufBinaryReader reader = new GgufBinaryReader(input);
                GgufBinaryWriter writer = new GgufBinaryWriter(out);

                reader.readInt32(); // magic
                // skip the remaining source header (version, counts, kv, tensor dir, padding)
                reader.skip(document.dataStartOffset - 4L);

                document.upsert("cortexj.converted_by", GgufBinaryReader.Types.STRING,
                        "cortexj-convert-gguf/" + direction.label);
                document.upsert("general.file_type", GgufBinaryReader.Types.INT32,
                        direction == Direction.TO_F16 ? Integer.valueOf(1) : Integer.valueOf(0));

                // temporarily swap tensor types to the converted ones so the shared
                // header writer computes the new offsets/sizes
                int[] originalTypes = new int[document.tensors.size()];
                for (int i = 0; i < document.tensors.size(); i++) {
                    GgufDocument.TensorInfo tensor = document.tensors.get(i);
                    originalTypes[i] = tensor.type;
                    int targetType = targetType(tensor.type, direction);
                    if (targetType != originalTypes[i]) {
                        converted++;
                    }
                    document.tensors.set(i, new GgufDocument.TensorInfo(
                            tensor.name, tensor.dimensions, targetType, tensor.offset));
                }
                document.writeHeader(writer);
                for (int i = 0; i < document.tensors.size(); i++) {
                    GgufDocument.TensorInfo tensor = document.tensors.get(i);
                    document.tensors.set(i, new GgufDocument.TensorInfo(
                            tensor.name, tensor.dimensions, originalTypes[i], tensor.offset));
                }

                byte[] buffer = new byte[1 << 16];
                for (int i = 0; i < document.tensors.size(); i++) {
                    GgufDocument.TensorInfo tensor = document.tensors.get(i);
                    int targetType = targetType(tensor.type, direction);
                    long declaredSize = tensor.byteSize();
                    if (targetType == tensor.type) {
                        copyExactly(reader, writer, declaredSize, buffer);
                    } else if (tensor.type == GgufDocument.TENSOR_F32
                            && targetType == GgufDocument.TENSOR_F16) {
                        convertFloats(reader, writer, tensor.elementCount(), true);
                    } else {
                        convertFloats(reader, writer, tensor.elementCount(), false);
                    }
                }
                writer.flush();
            }
            try {
                Files.move(temp, output, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING);
            }

            String sha256 = sha256Hex(output);
            Map<String, String> provenance = new LinkedHashMap<String, String>();
            provenance.put("converterId", ID);
            provenance.put("direction", direction.label);
            provenance.put("sourceArtifact", request.sourceArtifact().id());
            provenance.put("sourceBytes", String.valueOf(Files.size(source)));
            provenance.put("outputBytes", String.valueOf(Files.size(output)));
            provenance.put("tensorsConverted", String.valueOf(converted));
            provenance.put("tensorsTotal", String.valueOf(document.tensors.size()));
            provenance.put("outputChecksum", "sha256:" + sha256);

            Map<String, String> metadata = new LinkedHashMap<String, String>(request.sourceArtifact().metadata());
            metadata.put("quantization", direction.label);
            metadata.put("convertedFrom", request.sourceArtifact().id());
            ModelArtifact artifact = new ModelArtifact.Abstract(
                    output.getFileName().toString(), ArtifactFormat.GGUF,
                    Files.size(output), null, metadata) {
            };
            return new ConversionResult(artifact, output, provenance);
        } catch (IOException e) {
            throw new io.cortexj.error.ConversionException(
                    io.cortexj.ErrorCode.CONVERSION_FAILED,
                    "GGUF " + direction.label + " conversion failed: " + e.getMessage(),
                    "Verify the source file is a valid GGUF container with F32/F16 tensors.");
        }
    }

    private static int targetType(int sourceType, Direction direction) {
        if (direction == Direction.TO_F16 && sourceType == GgufDocument.TENSOR_F32) {
            return GgufDocument.TENSOR_F16;
        }
        if (direction == Direction.TO_F32 && sourceType == GgufDocument.TENSOR_F16) {
            return GgufDocument.TENSOR_F32;
        }
        return sourceType;
    }

    private static long alignedByteSize(GgufDocument.TensorInfo tensor, int targetType) {
        if (targetType == tensor.type) {
            return tensor.byteSize();
        }
        long elements = tensor.elementCount();
        return targetType == GgufDocument.TENSOR_F16 ? elements * 2L : elements * 4L;
    }

    private static void convertFloats(GgufBinaryReader reader, GgufBinaryWriter writer,
                                      long count, boolean toHalf) throws IOException {
        for (long i = 0; i < count; i++) {
            if (toHalf) {
                short half = Float16.toHalf(reader.readFloat32());
                writer.write(new byte[]{(byte) half, (byte) (half >> 8)});
            } else {
                float widened = Float16.fromHalf((short) reader.readUInt16());
                writer.writeFloat32(widened);
            }
        }
    }

    private static void copyExactly(GgufBinaryReader reader, GgufBinaryWriter writer,
                                    long bytes, byte[] buffer) throws IOException {
        long remaining = bytes;
        while (remaining > 0) {
            int chunk = (int) Math.min(buffer.length, remaining);
            byte[] data = reader.readBytes(chunk);
            writer.write(data);
            remaining -= chunk;
        }
    }

    private static long headerSize(GgufDocument document) {
        long size = 4L + 4L + 8L + 8L;
        for (GgufDocument.KeyValue kv : document.metadata) {
            size += 8L + kv.key.getBytes(StandardCharsets.UTF_8).length + 4L + valueSize(kv.type, kv.value);
        }
        for (GgufDocument.TensorInfo tensor : document.tensors) {
            size += 8L + tensor.name.getBytes(StandardCharsets.UTF_8).length + 4L
                    + 8L * tensor.dimensions.length + 4L + 8L;
        }
        return size;
    }

    private static long valueSize(int type, Object value) {
        switch (type) {
            case GgufBinaryReader.Types.UINT8:
            case GgufBinaryReader.Types.INT8:
            case GgufBinaryReader.Types.BOOL:
                return 1L;
            case GgufBinaryReader.Types.UINT16:
            case GgufBinaryReader.Types.INT16:
                return 2L;
            case GgufBinaryReader.Types.UINT32:
            case GgufBinaryReader.Types.INT32:
            case GgufBinaryReader.Types.FLOAT32:
                return 4L;
            case GgufBinaryReader.Types.UINT64:
            case GgufBinaryReader.Types.INT64:
            case GgufBinaryReader.Types.FLOAT64:
                return 8L;
            case GgufBinaryReader.Types.STRING:
                return 8L + ((String) value).getBytes(StandardCharsets.UTF_8).length;
            case GgufBinaryReader.Types.ARRAY:
                GgufBinaryReader.ArrayValue array = (GgufBinaryReader.ArrayValue) value;
                long size = 12L;
                for (Object element : array.values) {
                    size += valueSize(array.elementType, element);
                }
                return size;
            default:
                return 0L;
        }
    }

    private static String outputName(Path source, Direction direction) {
        String name = source.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        return base + "." + direction.label + extension;
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
}
