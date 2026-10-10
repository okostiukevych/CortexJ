package io.cortexj.train.distributed;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sharded checkpoints (Phase C piece, ADR-027): save/load round-trip,
 * checksum verification, typed failures.
 */
class CheckpointShardsTest {

    private static Map<String, double[]> state() {
        Map<String, double[]> state = new LinkedHashMap<>();
        state.put("layer.weight", new double[]{0.5, -0.25, 1.0, 0.0});
        state.put("layer.bias", new double[]{0.1, 0.2});
        state.put("head.weight", new double[]{2.0});
        return state;
    }

    @Test
    void roundTripKeepsEveryParameter() throws Exception {
        Path directory = Files.createTempDirectory("checkpoint");
        try {
            CheckpointShards.save(state(), directory, 2);
            Map<String, double[]> loaded = CheckpointShards.load(directory);
            assertEquals(state().keySet(), loaded.keySet());
            for (String name : state().keySet()) {
                assertArrayEquals(state().get(name), loaded.get(name), 1e-12);
            }
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    void corruptedShardFailsTyped() throws Exception {
        Path directory = Files.createTempDirectory("checkpoint");
        try {
            CheckpointShards.save(state(), directory, 2);
            Files.writeString(directory.resolve("shard-0.properties"), "tampered=true\n");
            assertThrows(java.io.IOException.class,
                    () -> CheckpointShards.load(directory));
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    void missingShardFailsTyped() throws Exception {
        Path directory = Files.createTempDirectory("checkpoint");
        try {
            CheckpointShards.save(state(), directory, 2);
            Files.deleteIfExists(directory.resolve("shard-1.properties"));
            java.io.IOException failure = assertThrows(java.io.IOException.class,
                    () -> CheckpointShards.load(directory));
            assertTrue(failure.getMessage().contains("missing"), failure.getMessage());
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    void missingManifestFailsTyped() throws Exception {
        Path directory = Files.createTempDirectory("checkpoint");
        try {
            java.io.IOException failure = assertThrows(java.io.IOException.class,
                    () -> CheckpointShards.load(directory));
            assertTrue(failure.getMessage().contains("manifest"), failure.getMessage());
        } finally {
            deleteRecursively(directory);
        }
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (Files.isDirectory(path)) {
            try (var stream = Files.list(path)) {
                for (Path child : stream.toList()) {
                    deleteRecursively(child);
                }
            }
        }
        Files.deleteIfExists(path);
    }
}
