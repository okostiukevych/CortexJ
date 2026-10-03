package io.cortexj.model.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.spi.artifact.ArtifactInspection;
import io.cortexj.spi.artifact.ArtifactInspector;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

public final class SafetensorsInspector implements ArtifactInspector {

    private static final int HEADER_LIMIT_BYTES = 64 * 1024 * 1024;

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public boolean supports(ArtifactFormat format) {
        return format == ArtifactFormat.SAFETENSORS;
    }

    @Override
    public ArtifactInspection inspect(Path artifact) {
        long fileSize;
        try {
            fileSize = Files.size(artifact);
        } catch (IOException e) {
            throw new MalformedArtifactException("Cannot read safetensors artifact " + artifact + ": " + e.getMessage(), e);
        }
        try (InputStream input = new BufferedInputStream(Files.newInputStream(artifact), 64 * 1024)) {
            return inspectStream(input, fileSize);
        } catch (IOException e) {
            throw new MalformedArtifactException("Cannot read safetensors artifact " + artifact + ": " + e.getMessage(), e);
        }
    }

    public ArtifactInspection inspectStream(InputStream input) throws IOException {
        return inspectStream(input, Long.MAX_VALUE);
    }

    private ArtifactInspection inspectStream(InputStream input, long availableBytes) throws IOException {
        byte[] lengthBytes = new byte[8];
        readFully(input, lengthBytes);
        long headerLength = ByteBuffer.wrap(lengthBytes).order(ByteOrder.LITTLE_ENDIAN).getLong();
        if (headerLength <= 0 || headerLength > HEADER_LIMIT_BYTES) {
            throw new MalformedArtifactException("Not a safetensors file: unreasonable header length " + headerLength);
        }
        if (availableBytes != Long.MAX_VALUE && headerLength > availableBytes - 8L) {
            throw new MalformedArtifactException("Not a safetensors file: declared header length " + headerLength
                    + " exceeds file size " + availableBytes);
        }
        byte[] headerBytes = new byte[(int) headerLength];
        readFully(input, headerBytes);
        JsonNode header = mapper.readTree(headerBytes);

        ArtifactInspection.Builder builder = ArtifactInspection.builder(ArtifactFormat.SAFETENSORS);
        String architecture = null;
        String quantization = null;
        int tensorCount = 0;
        long parameterCount = 0;

        JsonNode metadata = header.get("__metadata__");
        if (metadata != null && metadata.isObject()) {
            Iterator<String> fields = metadata.fieldNames();
            while (fields.hasNext()) {
                String field = fields.next();
                String value = metadata.get(field).asText("");
                builder.metadata(field, value);
                if (field.equalsIgnoreCase("architecture") || field.equalsIgnoreCase("architectures")) {
                    architecture = value;
                }
                if (field.equalsIgnoreCase("quantization") || field.equalsIgnoreCase("format")) {
                    quantization = value;
                }
            }
        }

        Iterator<String> tensorNames = header.fieldNames();
        while (tensorNames.hasNext()) {
            String name = tensorNames.next();
            if (name.equals("__metadata__")) {
                continue;
            }
            JsonNode tensor = header.get(name);
            JsonNode dtype = tensor.get("dtype");
            long[] shape = parseShape(tensor.get("shape"));
            long elements = 1L;
            for (long dimension : shape) {
                elements *= Math.max(1L, dimension);
            }
            parameterCount += elements;
            tensorCount++;
            if (dtype != null && quantization == null && name.toLowerCase().contains("weight")) {
                quantization = dtype.asText();
            }
        }

        if (architecture != null) {
            builder.architecture(architecture);
        }
        if (quantization != null) {
            builder.quantization(quantization);
        }
        builder.parameterCount(parameterCount);
        builder.metadata("tensorCount", String.valueOf(tensorCount));
        return builder.build();
    }

    private static long[] parseShape(JsonNode shapeNode) {
        if (shapeNode == null || !shapeNode.isArray()) {
            return new long[0];
        }
        long[] shape = new long[shapeNode.size()];
        for (int i = 0; i < shapeNode.size(); i++) {
            shape[i] = shapeNode.get(i).asLong();
        }
        return shape;
    }

    private static void readFully(InputStream input, byte[] target) throws IOException {
        int offset = 0;
        while (offset < target.length) {
            int read = input.read(target, offset, target.length - offset);
            if (read < 0) {
                throw new IOException("Truncated safetensors file");
            }
            offset += read;
        }
    }
}
