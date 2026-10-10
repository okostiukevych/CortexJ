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
        // audit 2026-10-07: hostile tokenizer.json must not OOM the parent -
        // cap file size and JSON nesting before parsing.
        long size = Files.size(tokenizerJson);
        if (size > 100L * 1024L * 1024L) {
            throw new IOException("tokenizer.json is unreasonably large (" + size + " bytes, cap 100MB)");
        }
        ObjectMapper mapper = new ObjectMapper();
        mapper.getFactory().setStreamReadConstraints(
                com.fasterxml.jackson.core.StreamReadConstraints.builder()
                        .maxNestingDepth(50)
                        .maxNumberLength(10000)
                        .maxStringLength(20 * 1024 * 1024)
                        .build());
        JsonNode root;
        try (java.io.InputStream input = Files.newInputStream(tokenizerJson)) {
            root = mapper.readTree(input);
        }
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

    /**
     * Whitespace/word fallback encoder used exactly when a model ships no
     * tokenizer.json (audit H-12). IDs are derived deterministically from a
     * 32-bit FNV-1a hash of each pre-token: no global lock on the embedding
     * hot path (pure function, trivially thread-safe) and no token-retention
     * map, so a distinct-token flood cannot grow the heap. Collisions across
     * different tokens are possible and acceptable for a fallback tokenizer -
     * the same token always maps to the same id, including across restarts.
     */
    public static TokenEncoder fallback() {
        return new TokenEncoder() {
            @Override
            public int[] encode(String text) {
                List<String> tokens = preTokens(text.toLowerCase());
                int[] ids = new int[tokens.size()];
                for (int i = 0; i < tokens.size(); i++) {
                    ids[i] = stableId(tokens.get(i));
                }
                return ids;
            }
        };
    }

    static int stableId(String token) {
        int hash = 0x811c9dc5;
        for (int i = 0; i < token.length(); i++) {
            hash ^= token.charAt(i);
            hash *= 0x01000193;
        }
        return hash & 0x7fffffff;
    }

    private static final class WordPiece implements TokenEncoder {

        private final Map<String, Integer> vocab;
        private final int longest;

        WordPiece(JsonNode model) throws java.io.IOException {
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

        Bpe(JsonNode model) throws java.io.IOException {
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
                encodeWord(word, ids);
            }
            return toArray(ids);
        }

        private void encodeWord(String word, List<Integer> ids) {
            int wordLength = word.length();
            List<String> parts = new ArrayList<String>(wordLength);
            for (char c : word.toCharArray()) {
                parts.add(String.valueOf(c));
            }
            // adjacent-pair occurrence index: a merge rule whose pair is absent
            // from the current parts is provably a no-op, so the rules scan
            // collapses from O(rules x parts) string comparisons to O(1)
            // lookups per rule (audit M-19); the merge order and therefore the
            // output are identical to the full-pass scan
            Map<String, Map<String, Integer>> pairCounts =
                    new java.util.HashMap<String, Map<String, Integer>>();
            for (int i = 0; i + 1 < parts.size(); i++) {
                incPair(pairCounts, parts.get(i), parts.get(i + 1));
            }
            for (String[] merge : merges) {
                Map<String, Integer> seconds = pairCounts.get(merge[0]);
                if (seconds == null || !seconds.containsKey(merge[1])) {
                    continue;
                }
                while (applyFirstOccurrence(parts, merge, pairCounts)) {
                    // keep merging while the pair occurs, exactly like the
                    // full-pass restart semantics
                }
            }
            for (String part : parts) {
                Integer id = vocab.get(part);
                ids.add(id == null ? Integer.valueOf(0) : id);
            }
        }

        private static boolean applyFirstOccurrence(List<String> parts, String[] merge,
                                                    Map<String, Map<String, Integer>> pairCounts) {
            int size = parts.size();
            for (int i = 0; i + 1 < size; i++) {
                if (merge[0].equals(parts.get(i)) && merge[1].equals(parts.get(i + 1))) {
                    String merged = merge[0] + merge[1];
                    String previous = i > 0 ? parts.get(i - 1) : null;
                    String next = i + 2 < size ? parts.get(i + 2) : null;
                    parts.set(i, merged);
                    parts.remove(i + 1);
                    decPair(pairCounts, merge[0], merge[1]);
                    if (previous != null) {
                        decPair(pairCounts, previous, merge[0]);
                        incPair(pairCounts, previous, merged);
                    }
                    if (next != null) {
                        decPair(pairCounts, merge[1], next);
                        incPair(pairCounts, merged, next);
                    }
                    return true;
                }
            }
            return false;
        }

        private static void incPair(Map<String, Map<String, Integer>> pairCounts,
                                    String first, String second) {
            Map<String, Integer> seconds = pairCounts.get(first);
            if (seconds == null) {
                seconds = new java.util.HashMap<String, Integer>();
                pairCounts.put(first, seconds);
            }
            Integer count = seconds.get(second);
            seconds.put(second, Integer.valueOf(count == null ? 1 : count + 1));
        }

        private static void decPair(Map<String, Map<String, Integer>> pairCounts,
                                    String first, String second) {
            Map<String, Integer> seconds = pairCounts.get(first);
            if (seconds == null) {
                return;
            }
            Integer count = seconds.get(second);
            if (count == null) {
                return;
            }
            if (count <= 1) {
                seconds.remove(second);
                if (seconds.isEmpty()) {
                    pairCounts.remove(first);
                }
            } else {
                seconds.put(second, Integer.valueOf(count - 1));
            }
        }
    }

    private static final class Unigram implements TokenEncoder {

        private final List<String> pieces;
        private final Map<Character, List<String>> piecesByFirstChar;
        private final Map<String, Integer> vocab;

        Unigram(JsonNode model) throws java.io.IOException {
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
            this.pieces = new ArrayList<String>(sortable.size());
            this.piecesByFirstChar = new java.util.HashMap<Character, List<String>>();
            for (String[] entry : sortable) {
                // audit #128: an empty piece (malformed tokenizer.json) must be
                // skipped, not crash the constructor with StringIndexOutOfBounds
                if (entry[0].isEmpty()) {
                    continue;
                }
                pieces.add(entry[0]);
                Character first = Character.valueOf(entry[0].charAt(0));
                List<String> bucket = piecesByFirstChar.get(first);
                if (bucket == null) {
                    bucket = new ArrayList<String>();
                    piecesByFirstChar.put(first, bucket);
                }
                bucket.add(entry[0]);
            }
        }

        @Override
        public int[] encode(String text) {
            List<Integer> ids = new ArrayList<Integer>();
            String lower = text.toLowerCase();
            int position = 0;
            while (position < lower.length()) {
                // audit M-19: index-based scan (no O(n^2) substring copying) and
                // a first-character piece index (the greedy longest-match order
                // inside a bucket is preserved, so the output is identical)
                String match = null;
                List<String> bucket = piecesByFirstChar.get(Character.valueOf(lower.charAt(position)));
                if (bucket != null) {
                    for (String piece : bucket) {
                        if (lower.startsWith(piece, position)) {
                            match = piece;
                            break;
                        }
                    }
                }
                if (match == null) {
                    match = lower.substring(position, position + 1);
                }
                Integer id = vocab.get(match);
                ids.add(id == null ? Integer.valueOf(0) : id);
                position += match.length();
            }
            return toArray(ids);
        }
    }

    private static final int MAX_VOCAB_ENTRIES = 2_000_000;

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

    private static Map<String, Integer> readVocab(JsonNode model) throws IOException {
        Map<String, Integer> vocab = new LinkedHashMap<String, Integer>();
        if (model.path("vocab").isObject()) {
            JsonNode vocabNode = model.path("vocab");
            java.util.Iterator<String> fields = vocabNode.fieldNames();
            int index = 0;
            while (fields.hasNext()) {
                // audit 2026-10-08: millions of tiny tokens would turn the
                // vocab map into GBs of heap despite the file-size cap
                if (index >= MAX_VOCAB_ENTRIES) {
                    throw new IOException("tokenizer vocab exceeds " + MAX_VOCAB_ENTRIES + " entries");
                }
                // real-model verification 2026-10-10: the JSON values are the
                // token ids - the document order is NOT guaranteed to match
                // them. Assigning positional indices silently mis-tokenizes
                // any vocab serialized out of id order.
                String name = fields.next();
                JsonNode idNode = vocabNode.get(name);
                int id = idNode != null && idNode.isInt() ? idNode.asInt() : index;
                // audit 2026-10-10: ids flow straight into ORT Gather - a
                // negative or absurd id must fail the tokenizer load, not
                // every inference (DoS) with an ORT error
                if (id < 0 || id > MAX_VOCAB_ENTRIES) {
                    throw new IOException("tokenizer vocab id out of range for '" + name
                            + "': " + id + " (allowed 0.." + MAX_VOCAB_ENTRIES + ")");
                }
                vocab.put(name, Integer.valueOf(id));
                index++;
            }
        } else {
            int index = 0;
            for (JsonNode token : model.path("vocab")) {
                // audit #128: a scalar entry inside an array vocab (malformed
                // tokenizer.json) must produce a skipped entry, not an NPE
                if (!token.isArray() || token.size() == 0 || !token.get(0).isTextual()) {
                    index++;
                    continue;
                }
                // audit 2026-10-08: see the object-vocab branch above
                if (index >= MAX_VOCAB_ENTRIES) {
                    throw new IOException("tokenizer vocab exceeds " + MAX_VOCAB_ENTRIES + " entries");
                }
                // array vocab entries are [piece, score] pairs: asText() on the
                // array node returns "" and collapsed every id to zero
                vocab.put(token.get(0).asText(), Integer.valueOf(index++));
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
