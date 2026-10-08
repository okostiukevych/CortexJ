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
}
