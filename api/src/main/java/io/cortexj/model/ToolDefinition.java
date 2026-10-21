package io.cortexj.model;

/**
 * One tool the caller declares as available to the model. The declaration is
 * opaque to CortexJ: {@code parametersJson} is the JSON schema the provider
 * understands, verbatim. CortexJ never executes tools - the caller receives
 * {@link ToolCall} proposals, executes them and feeds results back as
 * TOOL-role chat messages.
 */
public final class ToolDefinition {

    private final String name;
    private final String description;
    private final String parametersJson;

    private ToolDefinition(String name, String description, String parametersJson) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Tool definition name must not be blank");
        }
        this.name = name;
        this.description = description == null ? "" : description;
        this.parametersJson = parametersJson == null ? "" : parametersJson;
    }

    public static ToolDefinition function(String name, String description, String parametersJson) {
        return new ToolDefinition(name, description, parametersJson);
    }

    public String name() {
        return name;
    }

    /** Human-readable hint for the model, "" when absent. */
    public String description() {
        return description;
    }

    /** Raw JSON schema of the call arguments, "" when the tool takes none. */
    public String parametersJson() {
        return parametersJson;
    }

    @Override
    public String toString() {
        return name;
    }
}
