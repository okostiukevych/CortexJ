package io.cortexj.runtime.onnx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HfTokenizer {

    public interface Algorithm {
        List<String> tokenize(String text);
    }

    public static Algorithm load(Path tokenizerJson) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(Files.newInputStream(tokenizerJson));
        JsonNode model = root.path("model");
        String type = model.path("type").asText("");
        if (type.equals("WordPiece") || type.equals("BertWordPiece")) {
            return wordPiece(model);
        }
        if (type.equals("BPE")) {
            return bpe(model);
        }
        if (type.equals("Unigram")) {
            return unigram(model);
        }
        return whitespace();
    }

    private static Algorithm wordPiece(JsonNode model) {
        final Map<String, Integer> vocab = readVocab(model);
        final String unknown = model.path("unk_token").asText("[UNK]");
        final int longest = longestToken(vocab);
        return new Algorithm() {
            @Override
            public List<String> tokenize(String text) {
                List<String> tokens = new ArrayList<String>();
                for (String word : preTokens(text.toLowerCase())) {
                    int start = 0;
                    while (start < word.length()) {
                        int end = Math.min(word.length(), start + longest);
                        String match = null;
                        while (end > start) {
                            String candidate = start == 0
                                    ? word.substring(start, end)
                                    : "##" + word.substring(start, end);
                            if (vocab.containsKey(candidate)) {
                                match = candidate;
                                break;
                            }
                            end--;
                        }
                        if (match == null) {
                            tokens.add(unknown);
                            start++;
                        } else {
                            tokens.add(match);
                            start = end;
                        }
                    }
                }
                return tokens;
            }
        };
    }

    private static Algorithm bpe(JsonNode model) {
        final Map<String, Integer> vocab = readVocab(model);
        final List<String[]> merges = readMerges(model);
        return new Algorithm() {
            @Override
            public List<String> tokenize(String text) {
                List<String> tokens = new ArrayList<String>();
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
                        tokens.add(vocab.containsKey(part) ? part : model.path("unk_token").asText("<unk>"));
                    }
                }
                return tokens;
            }
        };
    }

    private static Algorithm unigram(JsonNode model) {
        final List<String[]> sorted = new ArrayList<String[]>();
        for (JsonNode entry : model.path("vocab")) {
            if (entry.isArray() && entry.size() == 2) {
                sorted.add(new String[]{entry.get(0).asText(), entry.get(1).asText()});
            }
        }
        sorted.sort(new Comparator<String[]>() {
            @Override
            public int compare(String[] a, String[] b) {
                return b[0].length() - a[0].length();
            }
        });
        return new Algorithm() {
            @Override
            public List<String> tokenize(String text) {
                List<String> tokens = new ArrayList<String>();
                String rest = text.toLowerCase();
                while (!rest.isEmpty()) {
                    String match = null;
                    for (String[] candidate : sorted) {
                        if (rest.startsWith(candidate[0])) {
                            match = candidate[0];
                            break;
                        }
                    }
                    if (match == null) {
                        match = rest.substring(0, 1);
                    }
                    tokens.add(match);
                    rest = rest.substring(match.length());
                }
                return tokens;
            }
        };
    }

    private static Algorithm whitespace() {
        return new Algorithm() {
            @Override
            public List<String> tokenize(String text) {
                return preTokens(text);
            }
        };
    }

    private static final Pattern PRE_TOKEN = Pattern.compile("[\\w]+|[^\\s\\w]");

    private static List<String> preTokens(String text) {
        List<String> parts = new ArrayList<String>();
        Matcher matcher = PRE_TOKEN.matcher(text);
        while (matcher.find()) {
            parts.add(matcher.group());
        }
        if (parts.isEmpty() && !text.trim().isEmpty()) {
            parts.addAll(Arrays.asList(text.trim().split("\\s+")));
        }
        return parts;
    }

    private static Map<String, Integer> readVocab(JsonNode model) {
        Map<String, Integer> vocab = new LinkedHashMap<String, Integer>();
        if (model.path("vocab").isObject()) {
            JsonNode vocabNode = model.path("vocab");
            java.util.Iterator<String> fields = vocabNode.fieldNames();
            int index = 0;
            while (fields.hasNext()) {
                vocab.put(fields.next(), Integer.valueOf(index++));
            }
        } else {
            int index = 0;
            for (JsonNode token : model.path("vocab")) {
                vocab.put(token.asText(), Integer.valueOf(index++));
            }
        }
        return vocab;
    }

    private static List<String[]> readMerges(JsonNode model) {
        List<String[]> merges = new ArrayList<String[]>();
        for (JsonNode merge : model.path("merges")) {
            String text = merge.asText("");
            String[] parts = text.split(" ");
            if (parts.length == 2) {
                merges.add(new String[]{parts[0], parts[1]});
            }
        }
        return merges;
    }

    private static int longestToken(Map<String, Integer> vocab) {
        int longest = 1;
        for (String token : vocab.keySet()) {
            longest = Math.max(longest, token.length());
        }
        return longest;
    }

    public static int[] encode(Algorithm algorithm, Map<String, Integer> vocab, String text) {
        List<String> tokens = algorithm.tokenize(text);
        List<Integer> ids = new ArrayList<Integer>();
        Integer cls = vocab.get("[CLS]");
        if (cls != null) {
            ids.add(cls);
        }
        for (String token : tokens) {
            Integer id = vocab.get(token);
            ids.add(id == null ? Integer.valueOf(0) : id);
        }
        Integer sep = vocab.get("[SEP]");
        if (sep != null) {
            ids.add(sep);
        }
        int[] result = new int[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            result[i] = ids.get(i).intValue();
        }
        return result;
    }

    public static Map<String, Integer> vocabOf(JsonNode model) {
        return readVocab(model);
    }

    private HfTokenizer() {
    }
}
