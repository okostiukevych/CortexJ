package io.cortexj.model.token;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class HfTokenizerEquivalenceTest {

    private static final Pattern PRE_TOKEN = Pattern.compile("[\\w]+|[^\\s\\w]");

    @TempDir
    Path tempDir;

    private static final String BPE_JSON = "{\"model\":{\"type\":\"BPE\",\"vocab\":{"
            + "\"l\":0,\"o\":1,\"lo\":2,\"w\":3,\"low\":4,\"r\":5,\"wor\":6,\"world\":7,"
            + "\"x\":8,\"y\":9,\"xy\":10,\"z\":11,\"xyz\":12,\"q\":13,\"qq\":14,\"zz\":15,"
            + "\"zzzz\":16,\"n\":17,\"e\":18,\"ne\":19,\"next\":20,\"a\":21,\"b\":22,\"<unk>\":23},"
            + "\"merges\":[\"l o\",\"lo w\",\"w o\",\"wo r\",\"wor l\",\"worl d\","
            + "\"x y\",\"xy z\",\"q q\",\"zz zz\",\"a b\",\"n e\",\"ne xt\"]}}";

    private static final String UNIGRAM_JSON = "{\"model\":{\"type\":\"Unigram\",\"vocab\":["
            + "[\"world\", 0.0],[\"next\", 0.0],[\"low\", 0.0],[\"wor\", 0.0],[\"lo\", 0.0],"
            + "[\"ab\", 0.0],[\"a\", 0.0],[\"b\", 0.0],[\"c\", 0.0],[\"d\", 0.0],[\"e\", 0.0],"
            + "[\"l\", 0.0],[\"n\", 0.0],[\"o\", 0.0],[\"r\", 0.0],[\"s\", 0.0],[\"t\", 0.0],"
            + "[\"w\", 0.0],[\"x\", 0.0],[\"y\", 0.0],[\"z\", 0.0]]}}";

    private static List<String> preTokens(String text) {
        List<String> parts = new ArrayList<String>();
        Matcher matcher = PRE_TOKEN.matcher(text);
        while (matcher.find()) {
            parts.add(matcher.group());
        }
        return parts;
    }

    private static Map<String, Integer> readVocab(JsonNode model) {
        Map<String, Integer> vocab = new LinkedHashMap<String, Integer>();
        if (model.path("vocab").isObject()) {
            java.util.Iterator<String> fields = vocabNode(model).fieldNames();
            int index = 0;
            while (fields.hasNext()) {
                vocab.put(fields.next(), Integer.valueOf(index++));
            }
        } else {
            int index = 0;
            for (JsonNode token : model.path("vocab")) {
                vocab.put(token.get(0).asText(), Integer.valueOf(index++));
            }
        }
        return vocab;
    }

    private static JsonNode vocabNode(JsonNode model) {
        return model.path("vocab");
    }

    /** Faithful copy of the pre-optimization BPE full-pass scan. */
    private static int[] naiveBpe(JsonNode root, String text) {
        JsonNode model = root.path("model");
        Map<String, Integer> vocab = readVocab(model);
        List<String[]> merges = new ArrayList<String[]>();
        for (JsonNode merge : model.path("merges")) {
            String[] parts = merge.asText("").split(" ");
            if (parts.length == 2) {
                merges.add(new String[]{parts[0], parts[1]});
            }
        }
        List<Integer> ids = new ArrayList<Integer>();
        for (String word : preTokens(text)) {
            List<String> parts = new ArrayList<String>();
            for (char c : word.toCharArray()) {
                parts.add(String.valueOf(c));
            }
            for (String[] merge : merges) {
                for (int i = 0; i + 1 < parts.size(); i++) {
                    if (parts.get(i).equals(merge[0]) && parts.get(i + 1).equals(merge[1])) {
                        parts.set(i, merge[0] + merge[1]);
                        parts.remove(i + 1);
                        i = Math.max(-1, i - 2);
                    }
                }
            }
            for (String part : parts) {
                Integer id = vocab.get(part);
                ids.add(id == null ? Integer.valueOf(0) : id);
            }
        }
        return toArray(ids);
    }

    /** Faithful copy of the pre-optimization unigram longest-match scan. */
    private static int[] naiveUnigram(JsonNode root, String text) {
        JsonNode model = root.path("model");
        Map<String, Integer> vocab = readVocab(model);
        List<String> pieces = new ArrayList<String>();
        List<String[]> sortable = new ArrayList<String[]>();
        for (JsonNode entry : model.path("vocab")) {
            if (entry.isArray() && entry.size() == 2) {
                sortable.add(new String[]{entry.get(0).asText(), entry.get(1).asText()});
            }
        }
        sortable.sort(new Comparator<String[]>() {
            @Override
            public int compare(String[] a, String[] b) {
                return b[0].length() - a[0].length();
            }
        });
        for (String[] entry : sortable) {
            pieces.add(entry[0]);
        }
        List<Integer> ids = new ArrayList<Integer>();
        String rest = text.toLowerCase();
        while (!rest.isEmpty()) {
            String match = null;
            for (String piece : pieces) {
                if (rest.startsWith(piece)) {
                    match = piece;
                    break;
                }
            }
            if (match == null) {
                match = rest.substring(0, 1);
            }
            Integer id = vocab.get(match);
            ids.add(id == null ? Integer.valueOf(0) : id);
            rest = rest.substring(match.length());
        }
        return toArray(ids);
    }

    private static int[] toArray(List<Integer> ids) {
        int[] result = new int[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            result[i] = ids.get(i).intValue();
        }
        return result;
    }

    @Test
    void bpeOutputsAreIdenticalToTheReferenceScan() throws IOException {
        JsonNode model = new ObjectMapper().readTree(BPE_JSON);
        HfTokenizer.TokenEncoder encoder = HfTokenizer.load(write(BPE_JSON, "tokenizer.json"));
        String[] corpus = {"low", "world", "xyz", "hello next", "qq zzzz", "ab", "lowworld",
                "next low", "wo", "world", "zzzz", "abc", "l o w"};
        for (String text : corpus) {
            assertArrayEquals(naiveBpe(model, text), encoder.encode(text),
                    "BPE output must be identical for '" + text + "'");
        }
    }

    @Test
    void unigramOutputsAreIdenticalToTheReferenceScan() throws IOException {
        JsonNode model = new ObjectMapper().readTree(UNIGRAM_JSON);
        HfTokenizer.TokenEncoder encoder = HfTokenizer.load(write(UNIGRAM_JSON, "unigram.json"));
        String[] corpus = {"world", "low next", "abc", "worlo", "next", "abab", "zzzz world"};
        for (String text : corpus) {
            assertArrayEquals(naiveUnigram(model, text), encoder.encode(text),
                    "unigram output must be identical for '" + text + "'");
        }
    }

    @Test
    void randomizedCorpusStaysEquivalent() throws IOException {
        JsonNode bpeModel = new ObjectMapper().readTree(BPE_JSON);
        JsonNode unigramModel = new ObjectMapper().readTree(UNIGRAM_JSON);
        HfTokenizer.TokenEncoder bpe = HfTokenizer.load(write(BPE_JSON, "tokenizer.json"));
        HfTokenizer.TokenEncoder unigram = HfTokenizer.load(write(UNIGRAM_JSON, "unigram.json"));
        String alphabet = "lowrdxyzqne abc";
        Random random = new Random(42L);
        for (int i = 0; i < 300; i++) {
            StringBuilder word = new StringBuilder();
            int length = 1 + random.nextInt(10);
            for (int j = 0; j < length; j++) {
                word.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            String text = word.toString();
            assertArrayEquals(naiveBpe(bpeModel, text), bpe.encode(text),
                    "BPE must stay equivalent for the random input '" + text + "'");
            assertArrayEquals(naiveUnigram(unigramModel, text), unigram.encode(text),
                    "unigram must stay equivalent for the random input '" + text + "'");
        }
    }

    private Path write(String json, String name) throws IOException {
        Path file = tempDir.resolve(name);
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));
        return file;
    }
}
