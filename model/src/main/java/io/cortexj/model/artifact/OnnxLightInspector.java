package io.cortexj.model.artifact;

import io.cortexj.model.ArtifactFormat;
import io.cortexj.spi.artifact.ArtifactInspection;
import io.cortexj.spi.artifact.ArtifactInspector;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class OnnxLightInspector implements ArtifactInspector {

    private static final int PROBE_LIMIT_BYTES = 1024 * 1024;

    @Override
    public boolean supports(ArtifactFormat format) {
        return format == ArtifactFormat.ONNX;
    }

    @Override
    public ArtifactInspection inspect(Path artifact) {
        try (InputStream input = Files.newInputStream(artifact)) {
            return inspectStream(input, Files.size(artifact));
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read ONNX artifact " + artifact + ": " + e.getMessage(), e);
        }
    }

    public ArtifactInspection inspectStream(InputStream input, long totalSizeBytes) throws IOException {
        ArtifactInspection.Builder builder = ArtifactInspection.builder(ArtifactFormat.ONNX);
        LimitedInputStream limited = new LimitedInputStream(input, PROBE_LIMIT_BYTES);

        try {
            while (true) {
                int tag = readVarint(limited);
                if (tag < 0) {
                    break;
                }
                int fieldNumber = tag >>> 3;
                int wireType = tag & 0x7;
                if (fieldNumber == 0) {
                    break;
                }
                switch (wireType) {
                    case 0: {
                        long value = readVarintLong(limited);
                        if (fieldNumber == 1) {
                            builder.metadata("irVersion", String.valueOf(value));
                        }
                        if (fieldNumber == 5) {
                            builder.metadata("modelVersion", String.valueOf(value));
                        }
                        break;
                    }
                    case 2: {
                        long length = readVarintLong(limited);
                        if (length < 0 || length > PROBE_LIMIT_BYTES) {
                            throw new IOException("Unreasonable ONNX field length: " + length);
                        }
                        byte[] payload = new byte[(int) length];
                        readFully(limited, payload);
                        if (fieldNumber == 2) {
                            builder.metadata("producer", new String(payload, java.nio.charset.StandardCharsets.UTF_8));
                        }
                        if (fieldNumber == 3) {
                            builder.metadata("producerVersion", new String(payload, java.nio.charset.StandardCharsets.UTF_8));
                        }
                        if (fieldNumber == 4) {
                            builder.metadata("domain", new String(payload, java.nio.charset.StandardCharsets.UTF_8));
                        }
                        if (fieldNumber == 6) {
                            builder.metadata("doc", new String(payload, java.nio.charset.StandardCharsets.UTF_8));
                        }
                        if (fieldNumber == 7) {
                            builder.metadata("graphPresent", "true");
                        }
                        break;
                    }
                    case 5: {
                        skip(limited, 4);
                        break;
                    }
                    case 1: {
                        skip(limited, 8);
                        break;
                    }
                    default:
                        throw new IOException("Unexpected ONNX wire type: " + wireType);
                }
            }
        } catch (TruncatedProbeException end) {
            builder.metadata("probe", "partial");
        } catch (IOException e) {
            builder.metadata("probe", "partial: " + e.getMessage());
        }
        if (totalSizeBytes > 0) {
            builder.parameterCount(totalSizeBytes / 2);
            builder.metadata("sizeBytes", String.valueOf(totalSizeBytes));
        }
        return builder.build();
    }

    private static int readVarint(InputStream input) throws IOException {
        return (int) readVarintLong(input);
    }

    private static long readVarintLong(InputStream input) throws IOException {
        long result = 0L;
        int shift = 0;
        while (shift < 64) {
            int read = input.read();
            if (read < 0) {
                throw new TruncatedProbeException();
            }
            result |= ((long) (read & 0x7F)) << shift;
            if ((read & 0x80) == 0) {
                return result;
            }
            shift += 7;
        }
        throw new IOException("Malformed varint");
    }

    private static void skip(InputStream input, long count) throws IOException {
        long skipped = 0;
        while (skipped < count) {
            long value = input.skip(count - skipped);
            if (value <= 0) {
                int read = input.read();
                if (read < 0) {
                    throw new TruncatedProbeException();
                }
                value = 1;
            }
            skipped += value;
        }
    }

    private static void readFully(InputStream input, byte[] target) throws IOException {
        int offset = 0;
        while (offset < target.length) {
            int read = input.read(target, offset, target.length - offset);
            if (read < 0) {
                throw new TruncatedProbeException();
            }
            offset += read;
        }
    }

    private static final class TruncatedProbeException extends IOException {
        private TruncatedProbeException() {
            super("probe limit reached");
        }
    }

    private static final class LimitedInputStream extends InputStream {

        private final InputStream delegate;
        private long remaining;

        LimitedInputStream(InputStream delegate, long limit) {
            this.delegate = delegate;
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int value = delegate.read();
            if (value >= 0) {
                remaining--;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int allowed = (int) Math.min(length, remaining);
            int read = delegate.read(buffer, offset, allowed);
            if (read > 0) {
                remaining -= read;
            }
            return read;
        }
    }
}
