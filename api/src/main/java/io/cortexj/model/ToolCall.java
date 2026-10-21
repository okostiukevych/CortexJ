package io.cortexj.model;

/**
 * One tool invocation proposed by the model: the id the provider assigned to
 * the call, the function name and the raw JSON argument string verbatim.
 * CortexJ never parses or re-serialises the arguments - the caller owns the
 * execution loop and knows the schema.
 *
 * <p>{@code id} may be empty: some providers do not assign stable ids to
 * streaming tool calls. {@code arguments} may be empty when nothing
 * parameterised was requested.</p>
 */
public final class ToolCall {

    private final String id;
    private final String name;
    private final String arguments;

    public ToolCall(String id, String name, String arguments) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Tool call name must not be blank");
        }
        this.id = id == null ? "" : id;
        this.name = name;
        this.arguments = arguments == null ? "" : arguments;
    }

    /** Provider-assigned call id, or "" when the provider did not assign one. */
    public String id() {
        return id;
    }

    /** Tool/function name, never blank. */
    public String name() {
        return name;
    }

    /** Raw JSON arguments string verbatim from the provider, "" when absent. */
    public String arguments() {
        return arguments;
    }

    @Override
    public String toString() {
        return name + "(" + arguments + (id.isEmpty() ? "" : ") id=" + id) + ")";
    }
}
