package io.cortexj.data.csv;

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
import java.util.List;

public final class CsvFrame {

    private CsvFrame() {
    }

    public static Frame read(Path path) throws IOException {
        String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        List<List<String>> records = parse(content);
        if (records.isEmpty()) {
            throw new IOException("CSV has no header row: " + path);
        }
        List<String> header = records.get(0);
        List<List<String>> body = records.subList(1, records.size());
        for (List<String> row : body) {
            if (row.size() != header.size()) {
                throw new IOException("CSV row has " + row.size() + " cells, header has "
                        + header.size() + ": " + path);
            }
        }
        List<ColType> types = inferTypes(header.size(), body);
        List<Column<?>> columns = new ArrayList<Column<?>>(header.size());
        for (int c = 0; c < header.size(); c++) {
            columns.add(column(header.get(c), types.get(c), body, c));
        }
        return new SimpleFrame(new Schema(header, types), columns);
    }

    private static Column<?> column(String name, ColType type, List<List<String>> body, int index) {
        switch (type) {
            case LONG: {
                List<Long> values = new ArrayList<Long>(body.size());
                for (List<String> row : body) {
                    String cell = row.get(index);
                    values.add(cell == null ? null : Long.valueOf(cell));
                }
                return new SimpleColumn<Long>(name, Long.class, values);
            }
            case DOUBLE: {
                List<Double> values = new ArrayList<Double>(body.size());
                for (List<String> row : body) {
                    String cell = row.get(index);
                    values.add(cell == null ? null : Double.valueOf(cell));
                }
                return new SimpleColumn<Double>(name, Double.class, values);
            }
            case BOOLEAN: {
                List<Boolean> values = new ArrayList<Boolean>(body.size());
                for (List<String> row : body) {
                    String cell = row.get(index);
                    values.add(cell == null ? null : Boolean.valueOf(cell));
                }
                return new SimpleColumn<Boolean>(name, Boolean.class, values);
            }
            case STRING:
            default: {
                List<String> values = new ArrayList<String>(body.size());
                for (List<String> row : body) {
                    values.add(row.get(index));
                }
                return new SimpleColumn<String>(name, String.class, values);
            }
        }
    }

    private static List<ColType> inferTypes(int width, List<List<String>> body) {
        List<ColType> types = new ArrayList<ColType>(width);
        for (int c = 0; c < width; c++) {
            boolean bool = true;
            boolean integer = true;
            boolean decimal = true;
            boolean seen = false;
            for (List<String> row : body) {
                String cell = row.get(c);
                if (cell == null || cell.isEmpty()) {
                    continue;
                }
                seen = true;
                if (!("true".equalsIgnoreCase(cell) || "false".equalsIgnoreCase(cell))) {
                    bool = false;
                }
                try {
                    Long.parseLong(cell);
                } catch (NumberFormatException notLong) {
                    integer = false;
                }
                try {
                    Double.parseDouble(cell);
                } catch (NumberFormatException notDouble) {
                    decimal = false;
                }
            }
            if (!seen) {
                types.add(ColType.STRING);
            } else if (bool) {
                types.add(ColType.BOOLEAN);
            } else if (integer) {
                types.add(ColType.LONG);
            } else if (decimal) {
                types.add(ColType.DOUBLE);
            } else {
                types.add(ColType.STRING);
            }
        }
        return types;
    }

    static List<List<String>> parse(String content) {
        List<List<String>> records = new ArrayList<List<String>>();
        List<String> row = new ArrayList<String>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        boolean empty = true;
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
                empty = false;
            } else if (ch == ',') {
                row.add(cellOrNull(cell, empty));
                cell.setLength(0);
                empty = true;
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < content.length() && content.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cellOrNull(cell, empty));
                cell.setLength(0);
                empty = true;
                if (!isBlankRow(row)) {
                    records.add(row);
                }
                row = new ArrayList<String>();
            } else {
                cell.append(ch);
                empty = false;
            }
        }
        row.add(cellOrNull(cell, empty));
        if (!isBlankRow(row)) {
            records.add(row);
        }
        return records;
    }

    private static String cellOrNull(StringBuilder cell, boolean empty) {
        // quoted-empty ("") is an empty string, bare-empty is missing
        return empty && cell.length() == 0 ? null : cell.toString();
    }

    private static boolean isBlankRow(List<String> row) {
        for (String cell : row) {
            if (cell != null && !cell.isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
