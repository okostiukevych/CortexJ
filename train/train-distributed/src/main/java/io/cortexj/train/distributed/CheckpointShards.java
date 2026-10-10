package io.cortexj.train.distributed;

import java.io.IOException;
import java.io.UncheckedIOException;
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
 * Sharded checkpoints (Phase C piece, ADR-027): a state dict split
 * into N shards plus a manifest with per-shard checksums. Loading
 * reassembles the dict and verifies every shard; a missing or
 * corrupted shard fails typed, never silently.
 */
public final class CheckpointShards {

    private CheckpointShards() {
    }

    /**
     * Saves the state dict into {@code shards} parts under the
     * directory, plus a {@code manifest.properties} with the checksum
     * of every shard.
     */
    public static List<Path> save(Map<String, double[]> state, Path directory, int shards)
            throws IOException {
        return saveWithShapes(state, null, directory, shards);
    }

    /**
     * Saves with per-parameter shapes recorded in the manifest
     * ({@code shape.<name>=d0,d1}) so a resume can rebuild tensors.
     */
    public static List<Path> saveWithShapes(Map<String, double[]> state,
                                            Map<String, long[]> shapes,
                                            Path directory, int shards)
            throws IOException {
        if (state == null || state.isEmpty()) {
            throw new IllegalArgumentException("state dict must not be empty");
        }
        if (shards < 1) {
            throw new IllegalArgumentException("shard count must be at least 1");
        }
        Files.createDirectories(directory);
        List<String> names = new ArrayList<>(state.keySet());
        Collections.sort(names);
        List<Path> files = new ArrayList<>(shards);
        StringBuilder manifest = new StringBuilder();
        manifest.append("shards=").append(shards).append('\n');
        if (shapes != null) {
            for (Map.Entry<String, long[]> shape : shapes.entrySet()) {
                manifest.append("shape.").append(shape.getKey()).append('=');
                long[] dimensions = shape.getValue();
                for (int i = 0; i < dimensions.length; i++) {
                    if (i > 0) {
                        manifest.append(',');
                    }
                    manifest.append(dimensions[i]);
                }
                manifest.append('\n');
            }
        }
        for (int shard = 0; shard < shards; shard++) {
            StringBuilder body = new StringBuilder();
            for (int i = shard; i < names.size(); i += shards) {
                String name = names.get(i);
                body.append(name).append('=');
                double[] values = state.get(name);
                for (int v = 0; v < values.length; v++) {
                    if (v > 0) {
                        body.append(',');
                    }
                    body.append(Double.toString(values[v]));
                }
                body.append('\n');
            }
            Path file = directory.resolve("shard-" + shard + ".properties");
            Files.writeString(file, body.toString(), StandardCharsets.UTF_8);
            files.add(file);
            manifest.append("shard.").append(shard).append(".checksum=")
                    .append(sha256Hex(Files.readAllBytes(file))).append('\n');
        }
        Files.writeString(directory.resolve("manifest.properties"),
                manifest.toString(), StandardCharsets.UTF_8);
        return files;
    }

    /**
     * Loads and verifies a sharded checkpoint: every shard present,
     * every checksum matching.
     */
    public static Map<String, double[]> load(Path directory) throws IOException {
        Path manifestFile = directory.resolve("manifest.properties");
        if (!Files.isRegularFile(manifestFile)) {
            throw new IOException("checkpoint manifest is missing: " + manifestFile);
        }
        Map<String, String> manifest = readProperties(manifestFile);
        int shards;
        try {
            shards = Integer.parseInt(manifest.getOrDefault("shards", "0"));
        } catch (NumberFormatException invalid) {
            throw new IOException("checkpoint manifest has an invalid shard count");
        }
        if (shards < 1) {
            throw new IOException("checkpoint manifest declares no shards");
        }
        Map<String, double[]> state = new LinkedHashMap<>();
        for (int shard = 0; shard < shards; shard++) {
            Path file = directory.resolve("shard-" + shard + ".properties");
            if (!Files.isRegularFile(file)) {
                throw new IOException("checkpoint shard is missing: " + file);
            }
            String expected = manifest.get("shard." + shard + ".checksum");
            String actual = sha256Hex(Files.readAllBytes(file));
            if (expected == null || !expected.equals(actual)) {
                throw new IOException("checkpoint shard checksum mismatch: " + file);
            }
            Map<String, String> entries = readProperties(file);
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                String[] parts = entry.getValue().split(",");
                double[] values = new double[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    values[i] = Double.parseDouble(parts[i]);
                }
                if (state.put(entry.getKey(), values) != null) {
                    throw new IOException("duplicate parameter across shards: " + entry.getKey());
                }
            }
        }
        return state;
    }

    /**
     * Reads the recorded shapes ({@code shape.<name>=d0,d1} manifest lines).
     */
    public static Map<String, long[]> shapesOf(Path directory) throws IOException {
        Path manifestFile = directory.resolve("manifest.properties");
        if (!Files.isRegularFile(manifestFile)) {
            throw new IOException("checkpoint manifest is missing: " + manifestFile);
        }
        Map<String, String> manifest = readProperties(manifestFile);
        Map<String, long[]> shapes = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : manifest.entrySet()) {
            if (!entry.getKey().startsWith("shape.")) {
                continue;
            }
            String[] parts = entry.getValue().split(",");
            long[] shape = new long[parts.length];
            for (int i = 0; i < parts.length; i++) {
                shape[i] = Long.parseLong(parts[i].trim());
            }
            shapes.put(entry.getKey().substring("shape.".length()), shape);
        }
        return shapes;
    }

    private static Map<String, String> readProperties(Path file) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int at = trimmed.indexOf('=');
            if (at < 0) {
                continue;
            }
            entries.put(trimmed.substring(0, at), trimmed.substring(at + 1));
        }
        return entries;
    }

    private static String sha256Hex(byte[] bytes) {
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
            throw new UncheckedIOException(new IOException("SHA-256 is required", e));
        }
    }
}
