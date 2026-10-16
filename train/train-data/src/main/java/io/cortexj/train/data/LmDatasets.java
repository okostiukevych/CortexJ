package io.cortexj.train.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cortexj.error.CortexJException;
import io.cortexj.train.LmDataset;
import io.cortexj.train.LmExample;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * JSONL corpora for LM fine-tuning. Format A (raw text): one
 * {"text": ...} per line, loss on everything. Format B (SFT): one
 * {"prompt": ..., "response": ...} per line, loss on the response
 * only (0.x convention: raw concatenation, no chat template).
 * Malformed lines fail typed with the line number (0110).
 */
public final class LmDatasets {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static LmDataset jsonlText(Path file, Tokenizer tokenizer, int maxSeqLen) {
        List<LmExample> examples = new ArrayList<LmExample>();
        int line = 0;
        for (String text : lines(file)) {
            line++;
            if (text.trim().isEmpty()) {
                continue;
            }
            JsonNode node = parse(file, line, text);
            String content = requiredString(file, line, node, "text");
            examples.add(encode("", content, tokenizer, maxSeqLen));
        }
        return dataset(examples, tokenizer, file);
    }

    public static LmDataset jsonlSft(Path file, Tokenizer tokenizer, int maxSeqLen) {
        List<LmExample> examples = new ArrayList<LmExample>();
        int line = 0;
        for (String text : lines(file)) {
            line++;
            if (text.trim().isEmpty()) {
                continue;
            }
            JsonNode node = parse(file, line, text);
            String prompt = requiredString(file, line, node, "prompt");
            String response = requiredString(file, line, node, "response");
            examples.add(encode(prompt, response, tokenizer, maxSeqLen));
        }
        return dataset(examples, tokenizer, file);
    }

    /**
     * Format C (chat SFT): one {"messages": [...]} per line, rendered
     * through a {@link ChatTemplates} preset; loss on the assistant
     * turn only (content + closing turn token).
     */
    public static LmDataset jsonlChat(Path file, Tokenizer tokenizer, int maxSeqLen,
                                      String templateId) {
        List<LmExample> examples = new ArrayList<LmExample>();
        int line = 0;
        for (String text : lines(file)) {
            line++;
            if (text.trim().isEmpty()) {
                continue;
            }
            JsonNode node = parse(file, line, text);
            JsonNode messages = node.get("messages");
            if (messages == null || !messages.isArray() || messages.size() == 0) {
                CortexJException failure = new CortexJException("CORTEXJ-TRAIN-0112",
                        "malformed chat dataset line: missing 'messages' array",
                        "One JSON object per line with a 'messages' array of"
                                + " {role, content} turns.");
                failure.detail("file", file.toString());
                failure.detail("line", String.valueOf(line));
                throw failure;
            }
            List<ChatTemplates.Message> turns = new ArrayList<ChatTemplates.Message>();
            for (JsonNode turn : messages) {
                turns.add(new ChatTemplates.Message(
                        turn.path("role").asText(""),
                        turn.path("content").asText("")));
            }
            ChatTemplates.Rendered rendered = ChatTemplates.render(templateId, turns);
            examples.add(encode(rendered.contextText(), rendered.responseText(),
                    tokenizer, maxSeqLen));
        }
        return dataset(examples, tokenizer, file);
    }

    /** Deterministic 90/10-style split; floor to the train side. */
    public static List<List<LmExample>> split(List<LmExample> examples,
                                               double trainFraction, long seed) {
        List<LmExample> shuffled = new ArrayList<LmExample>(examples);
        Collections.shuffle(shuffled, new java.util.Random(seed));
        int trainSize = Math.max(0, Math.min(shuffled.size(),
                (int) Math.floor(trainFraction * shuffled.size())));
        List<List<LmExample>> split = new ArrayList<List<LmExample>>(2);
        split.add(new ArrayList<LmExample>(shuffled.subList(0, trainSize)));
        split.add(new ArrayList<LmExample>(shuffled.subList(trainSize, shuffled.size())));
        return split;
    }

    /** sha256 over every (id, mask) pair - the dataset fingerprint for provenance. */
    public static String fingerprint(List<LmExample> examples) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (LmExample example : examples) {
                int[] tokens = example.tokens();
                int[] mask = example.lossMask();
                for (int i = 0; i < tokens.length; i++) {
                    digest.update((byte) (tokens[i] & 0xFF));
                    digest.update((byte) ((tokens[i] >>> 8) & 0xFF));
                    digest.update((byte) mask[i]);
                }
            }
            StringBuilder hex = new StringBuilder("sha256:");
            for (byte b : digest.digest()) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static LmExample encode(String prompt, String response, Tokenizer tokenizer,
                                    int maxSeqLen) {
        int[] promptIds = prompt.isEmpty() ? new int[0] : tokenizer.encode(prompt);
        int[] responseIds = response.isEmpty() ? new int[0] : tokenizer.encode(response);
        int total = Math.min(maxSeqLen, promptIds.length + responseIds.length);
        int[] tokens = new int[total];
        int[] mask = new int[total];
        int index = 0;
        for (int id : promptIds) {
            if (index >= total) {
                return LmExample.of(tokens, mask);
            }
            tokens[index] = id;
            mask[index] = 0;
            index++;
        }
        for (int id : responseIds) {
            if (index >= total) {
                break;
            }
            tokens[index] = id;
            mask[index] = 1;
            index++;
        }
        return LmExample.of(tokens, mask);
    }

    private static List<String> lines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CortexJException("CORTEXJ-TRAIN-0110",
                    "dataset file is not readable: " + file,
                    "Check the dataset path.");
        }
    }

    private static JsonNode parse(Path file, int line, String text) {
        try {
            return MAPPER.readTree(text);
        } catch (IOException e) {
            return fail(file, line, e.getMessage());
        }
    }

    private static String requiredString(Path file, int line, JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (value.isEmpty()) {
            fail(file, line, "missing field '" + field + "'");
        }
        return value;
    }

    private static JsonNode fail(Path file, int line, String reason) {
        CortexJException failure = new CortexJException("CORTEXJ-TRAIN-0110",
                "malformed dataset line: " + reason,
                "Fix the JSONL dataset: one JSON object per line.");
        failure.detail("file", file.toString());
        failure.detail("line", String.valueOf(line));
        throw failure;
    }

    private static LmDataset dataset(final List<LmExample> examples, final Tokenizer tokenizer,
                                     final Path file) {
        return new LmDataset() {
            @Override
            public List<LmExample> examples() {
                return examples;
            }

            @Override
            public int vocabSize() {
                return tokenizer.vocabSize();
            }

            @Override
            public String fingerprint() {
                return LmDatasets.fingerprint(examples);
            }

            @Override
            public String toString() {
                return "LmDataset(" + file + ", " + examples.size() + " examples)";
            }
        };
    }

    private LmDatasets() {
    }
}
