package io.cortexj.convert.gguf.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A fully parsed GGUF document: metadata KV pairs (typed, order-preserving),
 * the tensor directory and the byte offset where tensor data starts.
 */
public final class GgufDocument {

    public static final int GGUF_MAGIC = 0x46554747;

    public static final int TENSOR_F32 = 0;
    public static final int TENSOR_F16 = 1;
    public static final int TENSOR_BF16 = 30;

    // ggml block-quantized type ids (llama.cpp ggml.h). Element counts
    // below are per block, sizes are exact on-disk bytes per block.
    private static final int TENSOR_Q4_0 = 2;
    private static final int TENSOR_Q4_1 = 3;
    private static final int TENSOR_Q5_0 = 6;
    private static final int TENSOR_Q5_1 = 7;
    private static final int TENSOR_Q8_0 = 8;
    private static final int TENSOR_Q8_1 = 9;
    private static final int TENSOR_Q2_K = 10;
    private static final int TENSOR_Q3_K = 11;
    private static final int TENSOR_Q4_K = 12;
    private static final int TENSOR_Q5_K = 13;
    private static final int TENSOR_Q6_K = 14;
    private static final int TENSOR_Q8_K = 15;
    private static final int TENSOR_I8 = 24;
    private static final int TENSOR_I16 = 25;
    private static final int TENSOR_I32 = 26;
    private static final int TENSOR_I64 = 27;
    private static final int TENSOR_F64 = 28;

    private static final int MAX_TENSOR_DIMENSIONS = 63;
    // audit 2026-10-08: unbounded int64 counts allocate without bound below
    private static final long MAX_TENSOR_COUNT = 10_000_000L;
    private static final long MAX_METADATA_COUNT = 10_000_000L;

    public static final class KeyValue {

        public final String key;
        public int type;
        public Object value;

        public KeyValue(String key, int type, Object value) {
            this.key = key;
            this.type = type;
            this.value = value;
        }
    }

    public static final class TensorInfo {

        public final String name;
        public final long[] dimensions;
        public final int type;
        public final long offset;
        /**
         * Explicit on-disk byte size. Negative means "derive from the
         * type" (correct for F32/F16/BF16 only). Converters carrying
         * quantized tensors they do not decode set the true size here
         * so header offsets stay exact.
         */
        public final long explicitByteSize;

        public TensorInfo(String name, long[] dimensions, int type, long offset) {
            this(name, dimensions, type, offset, -1L);
        }

        public TensorInfo(String name, long[] dimensions, int type, long offset,
                          long explicitByteSize) {
            this.name = name;
            this.dimensions = dimensions;
            this.type = type;
            this.offset = offset;
            this.explicitByteSize = explicitByteSize;
        }

        public long elementCount() {
            // audit 2026-10-07: multiplyExact turns a hostile dimension
            // product into a typed failure instead of a silent wrap-around
            // that would understate byteSize() and mislead memory planning.
            long count = 1L;
            for (long dimension : dimensions) {
                if (dimension < 0) {
                    throw new ArithmeticException("negative GGUF tensor dimension: " + dimension);
                }
                count = Math.multiplyExact(count, dimension);
            }
            return count;
        }

        public long byteSize() {
            if (explicitByteSize >= 0) {
                return explicitByteSize;
            }
            switch (type) {
                case TENSOR_F32:
                    return Math.multiplyExact(elementCount(), 4L);
                case TENSOR_F16:
                case TENSOR_BF16:
                    return Math.multiplyExact(elementCount(), 2L);
                default:
                    return elementCount();
            }
        }
    }

    public int version;
    public final List<KeyValue> metadata = new ArrayList<KeyValue>();
    public final List<TensorInfo> tensors = new ArrayList<TensorInfo>();
    public long dataStartOffset;

    /**
     * Writes magic/version/counts, all metadata KV pairs, the tensor directory
     * (with data offsets starting at zero, 64-byte aligned) and the alignment padding.
     * After this call the stream is positioned at the start of tensor data.
     *
     * <p>The writer pads tensor data to 64 and declares it via
     * {@code general.alignment}, so any reader honoring the declaration
     * (including ours, defaulting to the 32 spec default) lands right.</p>
     */
    public long writeHeader(GgufBinaryWriter writer) throws IOException {
        upsert("general.alignment", 4, 64);
        writer.writeUInt32(GGUF_MAGIC);
        writer.writeUInt32(version);
        writer.writeInt64(tensors.size());
        writer.writeInt64(metadata.size());
        for (KeyValue kv : metadata) {
            writer.writeString(kv.key);
            writer.writeUInt32(kv.type);
            writer.writeValue(kv.type, kv.value);
        }
        long offset = 0L;
        for (TensorInfo tensor : tensors) {
            writer.writeString(tensor.name);
            writer.writeUInt32(tensor.dimensions.length);
            for (long dimension : tensor.dimensions) {
                writer.writeInt64(dimension);
            }
            writer.writeUInt32(tensor.type);
            writer.writeInt64(offset);
            offset += tensor.byteSize();
        }
        long header = headerSize(this);
        long padding = (64L - (header % 64L)) % 64L;
        writer.write(new byte[(int) padding]);
        return header + padding;
    }

    public static GgufDocument read(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            return read(input);
        }
    }

    public static GgufDocument read(InputStream input) throws IOException {
        GgufBinaryReader reader = new GgufBinaryReader(input);
        if (reader.readInt32() != GGUF_MAGIC) {
            throw new IOException("not a GGUF file: bad magic");
        }
        GgufDocument document = new GgufDocument();
        document.version = reader.readInt32();
        if (document.version < 2 || document.version > 3) {
            throw new IOException("unsupported GGUF version: " + document.version);
        }
        long tensorCount = reader.readInt64();
        long metadataCount = reader.readInt64();
        if (tensorCount < 0 || tensorCount > MAX_TENSOR_COUNT) {
            throw new GgufFormatException("unreasonable GGUF tensor count: " + tensorCount);
        }
        if (metadataCount < 0 || metadataCount > MAX_METADATA_COUNT) {
            throw new GgufFormatException("unreasonable GGUF metadata count: " + metadataCount);
        }

        for (long i = 0; i < metadataCount; i++) {
            String key = reader.readString();
            int type = reader.readInt32();
            Object value = reader.readValue(type);
            document.metadata.add(new KeyValue(key, type, value));
        }
        for (long i = 0; i < tensorCount; i++) {
            String name = reader.readString();
            int dimensions = reader.readInt32();
            if (dimensions < 1 || dimensions > MAX_TENSOR_DIMENSIONS) {
                throw new GgufFormatException("unreasonable GGUF tensor dimension count: " + dimensions);
            }
            long[] shape = new long[dimensions];
            for (int d = 0; d < dimensions; d++) {
                shape[d] = reader.readInt64();
            }
            int type = reader.readInt32();
            long offset = reader.readInt64();
            document.tensors.add(new TensorInfo(name, shape, type, offset,
                    quantizedByteSize(type, shape)));
        }
        long alignment = dataAlignment(document);
        long consumed = headerSize(document);
        long padding = (alignment - (consumed % alignment)) % alignment;
        reader.skip(padding);
        document.dataStartOffset = consumed + padding;
        return document;
    }

    /**
     * Real-model verification 2026-10-10: block-quantized tensors do NOT
     * occupy {@code elementCount()} bytes (Q4_K: 144 bytes per 256
     * elements, not 256). Without exact sizes every sequential tensor
     * reader desyncs mid-file and dies with a misleading "unexpected end
     * of GGUF data" on ordinary Q4_K_M models. Validated byte-exact
     * against Qwen2.5-0.5B-Q4_K_M (computed data section == file size).
     *
     * <p>Returns -1 (derive from the type) for F32/F16/BF16 and for
     * unknown/future types (IQ quants, TerraQuant, MXFP4, ...), preserving
     * the legacy guess there: silent corruption of an unknown layout is
     * worse than the loud failure the guess produces.
     */
    static long quantizedByteSize(int type, long[] shape) throws IOException {
        int blockElements;
        long blockBytes;
        switch (type) {
            case TENSOR_Q4_0:
                blockElements = 32;
                blockBytes = 18L;
                break;
            case TENSOR_Q4_1:
                blockElements = 32;
                blockBytes = 20L;
                break;
            case TENSOR_Q5_0:
                blockElements = 32;
                blockBytes = 22L;
                break;
            case TENSOR_Q5_1:
                blockElements = 32;
                blockBytes = 24L;
                break;
            case TENSOR_Q8_0:
                blockElements = 32;
                blockBytes = 34L;
                break;
            case TENSOR_Q8_1:
                blockElements = 32;
                blockBytes = 40L;
                break;
            case TENSOR_Q2_K:
                blockElements = 256;
                blockBytes = 84L;
                break;
            case TENSOR_Q3_K:
                blockElements = 256;
                blockBytes = 110L;
                break;
            case TENSOR_Q4_K:
                blockElements = 256;
                blockBytes = 144L;
                break;
            case TENSOR_Q5_K:
                blockElements = 256;
                blockBytes = 176L;
                break;
            case TENSOR_Q6_K:
                blockElements = 256;
                blockBytes = 210L;
                break;
            case TENSOR_Q8_K:
                blockElements = 256;
                blockBytes = 292L;
                break;
            case TENSOR_I8:
                return byteSizeOf(shape, 1L);
            case TENSOR_I16:
                return byteSizeOf(shape, 2L);
            case TENSOR_I32:
                return byteSizeOf(shape, 4L);
            case TENSOR_I64:
            case TENSOR_F64:
                return byteSizeOf(shape, 8L);
            default:
                return -1L;
        }
        long elements = 1L;
        for (long dimension : shape) {
            if (dimension < 0) {
                throw new GgufFormatException("negative GGUF tensor dimension: " + dimension);
            }
            elements = Math.multiplyExact(elements, dimension);
        }
        if (elements % blockElements != 0) {
            throw new GgufFormatException("GGUF tensor of type " + type + " has " + elements
                    + " elements, not a multiple of the block size " + blockElements);
        }
        try {
            return Math.multiplyExact(elements / blockElements, blockBytes);
        } catch (ArithmeticException overflow) {
            // audit 2026-10-10: the unchecked exception bypassed the
            // GgufFormatException handlers and surfaced untyped
            throw new GgufFormatException("GGUF tensor size overflow for type " + type);
        }
    }

    private static long byteSizeOf(long[] shape, long bytesPerElement) throws IOException {
        long elements = 1L;
        for (long dimension : shape) {
            if (dimension < 0) {
                throw new GgufFormatException("negative GGUF tensor dimension: " + dimension);
            }
            elements = Math.multiplyExact(elements, dimension);
        }
        try {
            return Math.multiplyExact(elements, bytesPerElement);
        } catch (ArithmeticException overflow) {
            // audit 2026-10-10: see quantizedByteSize - typed, not unchecked
            throw new GgufFormatException("GGUF tensor size overflow (" + elements
                    + " elements at " + bytesPerElement + " bytes)");
        }
    }

    /**
     * Tensor-data alignment: the {@code general.alignment} metadata when
     * declared, the GGUF default 32 otherwise. Real llama.cpp files use
     * 32; assuming 64 shifts every tensor read (found live: 32 bytes).
     */
    static long dataAlignment(GgufDocument document) {
        for (KeyValue kv : document.metadata) {
            if ("general.alignment".equals(kv.key) && kv.value instanceof Number) {
                long alignment = ((Number) kv.value).longValue();
                if (alignment == 8 || alignment == 16 || alignment == 32
                        || alignment == 64 || alignment == 128) {
                    return alignment;
                }
            }
        }
        return 32L;
    }

    private static long stringSize(String value) {
        return 8L + value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
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
                return stringSize((String) value);
            case GgufBinaryReader.Types.ARRAY:
                GgufBinaryReader.ArrayValue array = (GgufBinaryReader.ArrayValue) value;
                long size = 4L + 8L;
                for (Object element : array.values) {
                    size += valueSize(array.elementType, element);
                }
                return size;
            default:
                return 0L;
        }
    }

    public static long headerSize(GgufDocument document) {
        long size = 4L + 4L + 8L + 8L;
        for (KeyValue kv : document.metadata) {
            size += stringSize(kv.key) + 4L + valueSize(kv.type, kv.value);
        }
        for (TensorInfo tensor : document.tensors) {
            size += stringSize(tensor.name) + 4L + 8L * tensor.dimensions.length + 4L + 8L;
        }
        return size;
    }

    public KeyValue find(String key) {
        for (KeyValue kv : metadata) {
            if (kv.key.equals(key)) {
                return kv;
            }
        }
        return null;
    }

    public void upsert(String key, int type, Object value) {
        KeyValue existing = find(key);
        if (existing != null) {
            existing.type = type;
            existing.value = value;
            return;
        }
        metadata.add(new KeyValue(key, type, value));
    }
}
