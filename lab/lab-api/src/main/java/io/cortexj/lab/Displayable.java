package io.cortexj.lab;

import java.util.List;

/**
 * One rendered cell: content plus its MIME type (for rich display in
 * notebook frontends; plain {@code text/plain} for terminals).
 */
public final class Displayable {

    private final String mimeType;
    private final String content;

    public Displayable(String mimeType, String content) {
        if (mimeType == null || mimeType.isEmpty()) {
            throw new IllegalArgumentException("MIME type must not be empty");
        }
        this.mimeType = mimeType;
        this.content = content == null ? "" : content;
    }

    public String mimeType() {
        return mimeType;
    }

    public String content() {
        return content;
    }

    public static Displayable text(String content) {
        return new Displayable("text/plain", content);
    }

    public static Displayable table(io.cortexj.data.Frame frame) {
        StringBuilder rendered = new StringBuilder();
        List<String> names = frame.schema().names();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                rendered.append(" | ");
            }
            rendered.append(names.get(i));
        }
        rendered.append('\n');
        for (List<String> row : frame.rows()) {
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) {
                    rendered.append(" | ");
                }
                rendered.append(row.get(i) == null ? "<null>" : row.get(i));
            }
            rendered.append('\n');
        }
        return new Displayable("text/plain", rendered.toString());
    }

    public static Displayable plan(io.cortexj.planner.ExecutionPlan plan) {
        return new Displayable("text/plain", plan == null ? "" : plan.explanation());
    }

    /**
     * ASCII bar chart over one numeric column (terminal- and
     * notebook-safe rendering; rich chart widgets are frontends'
     * job, the data contract is this text).
     */
    public static Displayable chart(io.cortexj.data.Frame frame, String column) {
        return chart(frame, column, 40);
    }

    public static Displayable chart(io.cortexj.data.Frame frame, String column, int width) {
        if (frame == null || column == null) {
            throw new IllegalArgumentException("frame and column must not be null");
        }
        io.cortexj.data.Column<?> values = frame.column(column);
        double max = Double.NEGATIVE_INFINITY;
        for (int r = 0; r < values.size(); r++) {
            Object value = values.get(r);
            if (value instanceof Number) {
                max = Math.max(max, Math.abs(((Number) value).doubleValue()));
            }
        }
        if (max <= 0 || width < 8) {
            throw new IllegalArgumentException("chart needs positive values and width >= 8");
        }
        StringBuilder chart = new StringBuilder();
        chart.append(column).append('\n');
        for (int r = 0; r < values.size(); r++) {
            Object value = values.get(r);
            double number = value instanceof Number ? ((Number) value).doubleValue() : 0;
            int bars = (int) Math.round(Math.abs(number) / max * (width - 10));
            chart.append(String.format("%8.3f |", number));
            for (int b = 0; b < bars; b++) {
                chart.append(number < 0 ? '-' : '#');
            }
            chart.append('\n');
        }
        return new Displayable("text/plain", chart.toString());
    }
}
