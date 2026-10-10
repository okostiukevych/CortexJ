package io.cortexj.data.json;

import io.cortexj.data.ColType;
import io.cortexj.data.Fingerprint;
import io.cortexj.data.Frame;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonLinesFrameTest {

    private static Path reviews() throws Exception {
        Path file = Files.createTempFile("reviews", ".jsonl");
        Files.writeString(file, "{\"id\": 1, \"stars\": 5, \"text\": \"loved it\"}\n"
                + "{\"id\": 2, \"stars\": 1, \"verified\": false, \"text\": \"hated, \\\"it\\\"\"}\n"
                + "{\"id\": 3, \"stars\": 4, \"verified\": null}\n", StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void readsFlatObjectsWithEscapes() throws Exception {
        Path file = reviews();
        try {
            Frame frame = JsonLinesFrame.read(file);
            assertEquals(Arrays.asList("id", "stars", "text", "verified"),
                    frame.schema().names());
            assertEquals(Arrays.asList(ColType.LONG, ColType.LONG, ColType.STRING,
                    ColType.BOOLEAN), frame.schema().types());
            assertEquals(3, frame.rowCount());
            assertEquals("hated, \"it\"", frame.column("text").get(1));
            assertNull(frame.column("text").get(2));
            assertNull(frame.column("verified").get(0));
            assertEquals(Fingerprint.ofFrame(frame), Fingerprint.ofFrame(JsonLinesFrame.read(file)));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void nestedValuesFailTyped() throws Exception {
        Path file = Files.createTempFile("nested", ".jsonl");
        try {
            Files.writeString(file, "{\"id\": 1, \"tags\": [\"a\", \"b\"]}\n",
                    StandardCharsets.UTF_8);
            assertThrows(java.io.IOException.class, () -> JsonLinesFrame.read(file));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void garbageLinesFailTyped() throws Exception {
        Path file = Files.createTempFile("garbage", ".jsonl");
        try {
            Files.writeString(file, "not json at all\n", StandardCharsets.UTF_8);
            java.io.IOException failure = assertThrows(java.io.IOException.class,
                    () -> JsonLinesFrame.read(file));
            assertTrue(failure.getMessage() != null);
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
