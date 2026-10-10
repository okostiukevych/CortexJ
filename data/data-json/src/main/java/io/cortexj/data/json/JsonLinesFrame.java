package io.cortexj.data.json;

import io.cortexj.data.ColType;
import io.cortexj.data.Column;
import io.cortexj.data.Frame;
import io.cortexj.data.Schema;
import io.cortexj.data.SimpleColumn;
import io.cortexj.data.SimpleFrame;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON Lines adapter (ADR-019): one flat JSON object per line becomes
 * a frame row; keys become columns in first-seen order. Nested
 * objects and arrays fail typed (a flat frame cannot hold them).
 * Zero third-party dependencies, no Jackson.
 */
public final class JsonLinesFrame {

    private JsonLinesFrame() {
    }

    public static Frame read(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        List<Map<String, String>> records = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            records.add(parseObject(trimmed, path));
        }
        if (records.isEmpty()) {
            throw new IOException("JSONL file has no records: " + path);
        }
        List<String> names = new ArrayList<>();
        for (Map<String, String> record : records) {
            for (String key : record.keySet()) {
                if (!names.contains(key)) {
                    names.add(key);
                }
            }
        }
        List<ColType> types = new ArrayList<>();
        for (String name : names) {
            types.add(inferType(records, name));
        }
        List<Column<?>> columns = new ArrayList<>();
        for (int c = 0; c < names.size(); c++) {
            columns.add(typedColumn(names.get(c), types.get(c), records));
        }
        return new SimpleFrame(new Schema(names, types), columns);
    }

    private static ColType inferType(List<Map<String, String>> records, String name) {
        boolean seen = false;
        boolean integer = true;
        boolean decimal = true;
        boolean bool = true;
        for (Map<String, String> record : records) {
            String value = record.get(name);
            if (value == null) {
                continue;
            }
            seen = true;
            if (!("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value))) {
                bool = false;
            }
            try {
                Long.parseLong(value);
            } catch (NumberFormatException notLong) {
                integer = false;
            }
            try {
                Double.parseDouble(value);
            } catch (NumberFormatException notDouble) {
                decimal = false;
            }
        }
        if (!seen) {
            return ColType.STRING;
        }
        if (bool) {
            return ColType.BOOLEAN;
        }
        if (integer) {
            return ColType.LONG;
        }
        if (decimal) {
            return ColType.DOUBLE;
        }
        return ColType.STRING;
    }

    private static Column<?> typedColumn(String name, ColType type,
                                         List<Map<String, String>> records) {
        switch (type) {
            case LONG: {
                List<Long> values = new ArrayList<>();
                for (Map<String, String> record : records) {
                    String cell = record.get(name);
                    values.add(cell == null ? null : Long.valueOf(cell));
                }
                return new SimpleColumn<Long>(name, Long.class, values);
            }
            case DOUBLE: {
                List<Double> values = new ArrayList<>();
                for (Map<String, String> record : records) {
                    String cell = record.get(name);
                    values.add(cell == null ? null : Double.valueOf(cell));
                }
                return new SimpleColumn<Double>(name, Double.class, values);
            }
            case BOOLEAN: {
                List<Boolean> values = new ArrayList<>();
                for (Map<String, String> record : records) {
                    String cell = record.get(name);
                    values.add(cell == null ? null : Boolean.valueOf(cell));
                }
                return new SimpleColumn<Boolean>(name, Boolean.class, values);
            }
            case STRING:
            default: {
                List<String> values = new ArrayList<>();
                for (Map<String, String> record : records) {
                    values.add(record.get(name));
                }
                return new SimpleColumn<String>(name, String.class, values);
            }
        }
    }

    static Map<String, String> parseObject(String line, Path path) throws IOException {
        String trimmed = line.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            throw new IOException("not a JSON object line in " + path + ": " + abbrev(line));
        }
        Map<String, String> record = new LinkedHashMap<>();
        int i = 1;
        int end = trimmed.length() - 1;
        while (i < end) {
            i = skipWhitespace(trimmed, i);
            if (i >= end) {
                break;
            }
            if (trimmed.charAt(i) != '"') {
                throw new IOException("expected a string key in " + path + ": " + abbrev(line));
            }
            StringBuilder key = new StringBuilder();
            i = readString(trimmed, i, key);
            i = skipWhitespace(trimmed, i);
            expect(trimmed, i, ':', path, line);
            i = skipWhitespace(trimmed, i + 1);
            String value;
            if (i < end && trimmed.charAt(i) == '"') {
                StringBuilder text = new StringBuilder();
                i = readString(trimmed, i, text);
                value = text.toString();
            } else {
                int start = i;
                while (i < end && trimmed.charAt(i) != ',' && trimmed.charAt(i) != '}') {
                    i++;
                }
                value = trimmed.substring(start, i).trim();
            }
            if (value.startsWith("{") || value.startsWith("[")) {
                throw new IOException("nested values are not flat frames (" + path + "): "
                        + abbrev(line));
            }
            if ("null".equals(value)) {
                record.put(key.toString(), null);
            } else {
                record.put(key.toString(), value);
            }
            i = skipWhitespace(trimmed, i);
            if (i < end && trimmed.charAt(i) == ',') {
                i++;
            }
        }
        return record;
    }

    private static int skipWhitespace(String text, int i) {
        while (i < text.length()
                && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
            i++;
        }
        return i;
    }

    private static int readString(String text, int quote, StringBuilder target) throws IOException {
        int i = quote + 1;
        StringBuilder current = target;
        while (i < text.length()) {
            char ch = text.charAt(i);
            if (ch == '\\' && i + 1 < text.length()) {
                char next = text.charAt(i + 1);
                switch (next) {
                    case '"':
                        current.append('"');
                        break;
                    case '\\':
                        current.append('\\');
                        break;
                    case 'n':
                        current.append('\n');
                        break;
                    case 't':
                        current.append('\t');
                        break;
                    default:
                        current.append(next);
                }
                i += 2;
                continue;
            }
            if (ch == '"') {
                return i + 1;
            }
            current.append(ch);
            i++;
        }
        throw new IOException("unterminated string");
    }

    private static void expect(String text, int at, char expected, Path path, String line)
            throws IOException {
        if (at >= text.length() || text.charAt(at) != expected) {
            throw new IOException("expected '" + expected + "' in " + path + ": " + abbrev(line));
        }
    }

    private static String abbrev(String line) {
        return line.length() <= 80 ? line : line.substring(0, 80) + "...";
    }
}
