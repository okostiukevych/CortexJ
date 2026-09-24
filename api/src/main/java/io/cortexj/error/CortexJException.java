package io.cortexj.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

public class CortexJException extends RuntimeException {

    private final String code;
    private final Map<String, String> details = new LinkedHashMap<String, String>();
    private final List<String> remediation = new ArrayList<String>();

    public CortexJException(String code, String message) {
        super(message);
        this.code = code;
    }

    public CortexJException(String code, String message, String remediationSuggestion) {
        super(message);
        this.code = code;
        if (remediationSuggestion != null) {
            remediation.add(remediationSuggestion);
        }
    }

    public CortexJException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public CortexJException detail(String key, String value) {
        if (value != null) {
            details.put(key, value);
        }
        return this;
    }

    public CortexJException remediate(String suggestion) {
        if (suggestion != null) {
            remediation.add(suggestion);
        }
        return this;
    }

    public Map<String, String> details() {
        return Collections.unmodifiableMap(details);
    }

    public List<String> remediation() {
        return Collections.unmodifiableList(remediation);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(code).append(": ").append(getMessage());
        if (!details.isEmpty()) {
            sb.append("\nDetails:");
            for (Map.Entry<String, String> entry : details.entrySet()) {
                sb.append("\n  ").append(entry.getKey()).append(": ").append(entry.getValue());
            }
        }
        if (!remediation.isEmpty()) {
            sb.append("\nRemediation:");
            int index = 1;
            for (String suggestion : remediation) {
                sb.append("\n  ").append(index++).append(". ").append(suggestion);
            }
        }
        return sb.toString();
    }
}
