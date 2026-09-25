package io.cortexj.model.token;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HfTokenizer {

    public interface TokenEncoder {
        int[] encode(String text);
    }

    public static TokenEncoder load(Path tokenizerJson) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(Files.newInputStream(tokenizerJson));
        JsonNode model = root.path("model");
        String type = model.path("type").asText("");
        if (type.equals("WordPiece") || type.equals("BertWordPiece")) {
            return new WordPiece(model);
        }
        if (type.equals("BPE")) {
            return new Bpe(model);
        }
        if (type.equals("Unigram")) {
            return new Unigram(model);
        }
        return fallback();
    }

    public static TokenEncoder fallback() {
        return new TokenEncoder() {
            private final Map<String, Integer> observed = new LinkedHashMap<String, Integer>();

            @Override
            public synchronized int[] encode(String text) {
                List<String> tokens = preTokens(text.toLowerCase());
                int[] ids = new int[tokens.size()];
                for (int i = 0; i < tokens.size(); i++) {
                    Integer id = observed.get(tokens.get(i));
                    if (id == null) {
                        id = Integer.valueOf(observed.size());
                        observed.put(tokens.get(i), id);
                    }
                    ids[i] = id.intValue();
                }
                return ids;
            }
        };
    }

    private static final class WordPiece implements TokenEncoder {

        private final Map<String, Integer> vocab;
        private final int longest;

        WordPiece(JsonNode model) {
            this.vocab = readVocab(model);
            int longest = 1;
            for (String token : vocab.keySet()) {
                longest = Math.max(longest, token.length());
            }
            this.longest = longest;
        }

        @Override
        public int[] encode(String text) {
            List<Integer> ids = new ArrayList<Integer>();
            Integer cls = vocab.get("[CLS]");
            Integer sep = vocab.get("[SEP]");
            if (cls != null) {
                ids.add(cls);
            }
            for (String word : preTokens(text.toLowerCase())) {
                int start = 0;
                while (start < word.length()) {
                    int end = Math.min(word.length(), start + longest);
                    Integer match = null;
                    while (end > start) {
                        String candidate = start == 0
                                ? word.substring(start, end)
                                : "##" + word.substring(start, end);
                        match = vocab.get(candidate);
                        if (match != null) {
                            break;
                        }
                        end--;
                    }
                    if (match == null) {
                        ids.add(Integer.valueOf(0));
                        start++;
                    } else {
                        ids.add(match);
                        start = end;
                    }
                }
            }
            if (sep != null) {
                ids.add(sep);
            }
            return toArray(ids);
        }
    }

    private static final class Bpe implements TokenEncoder {

        private final Map<String, Integer> vocab;
        private final List<String[]> merges;

        Bpe(JsonNode model) {
            this.vocab = readVocab(model);
            this.merges = new ArrayList<String[]>();
            for (JsonNode merge : model.path("merges")) {
                String[] parts = merge.asText("").split(" ");
                if (parts.length == 2) {
                    merges.add(new String[]{parts[0], parts[1]});
                }
            }
        }

        @Override
        public int[] encode(String text) {
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
    }

    private static final class Unigram implements TokenEncoder {

        private final List<String> pieces;
        private final Map<String, Integer> vocab;

        Unigram(JsonNode model) {
            this.pieces = new ArrayList<String>();
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
            this.vocab = readVocab(model);
            for (String[] entry : sortable) {
                pieces.add(entry[0]);
            }
        }

        @Override
        public int[] encode(String text) {
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

    private static int[] toArray(List<Integer> ids) {
        int[] result = new int[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            result[i] = ids.get(i).intValue();
        }
        return result;
    }

    private HfTokenizer() {
    }
}
