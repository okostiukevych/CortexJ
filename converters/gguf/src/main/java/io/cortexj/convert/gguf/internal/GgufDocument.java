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

        public TensorInfo(String name, long[] dimensions, int type, long offset) {
            this.name = name;
            this.dimensions = dimensions;
            this.type = type;
            this.offset = offset;
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
     */
    public long writeHeader(GgufBinaryWriter writer) throws IOException {
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
            document.tensors.add(new TensorInfo(name, shape, type, offset));
        }
        long consumed = headerSize(document);
        long padding = (64L - (consumed % 64L)) % 64L;
        reader.skip(padding);
        document.dataStartOffset = consumed + padding;
        return document;
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
