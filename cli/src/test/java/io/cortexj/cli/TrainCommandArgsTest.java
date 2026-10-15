package io.cortexj.cli;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TrainCommandArgsTest {

    @Test
    void parsesTheFullSurface() {
        Map<String, String> parsed = TrainCommand.parse(new String[]{
                "train", "/models/qwen3-0.6b", "--dataset", "sft.jsonl", "--format", "sft",
                "--rank", "8", "--alpha", "16", "--targets", "q_proj,v_proj",
                "--epochs", "3", "--lr", "0.0002", "--out", "/tmp/adapter"});
        assertEquals("/models/qwen3-0.6b", parsed.get("modelPath"));
        assertEquals("sft.jsonl", parsed.get("dataset"));
        assertEquals("sft", parsed.get("format"));
        assertEquals("8", parsed.get("rank"));
        assertEquals("16", parsed.get("alpha"));
        assertEquals("q_proj,v_proj", parsed.get("targets"));
        assertEquals("3", parsed.get("epochs"));
        assertEquals("0.0002", parsed.get("lr"));
        assertEquals("/tmp/adapter", parsed.get("out"));
    }

    @Test
    void missingModelPathFails() {
        assertThrows(IllegalArgumentException.class,
                () -> TrainCommand.parse(new String[]{"train"}));
    }
}
