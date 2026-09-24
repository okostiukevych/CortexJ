package io.cortexj.error;

public class InferenceException extends CortexJException {
    public InferenceException(String code, String message) { super(code, message); }
    public InferenceException(String code, String message, String remediation) { super(code, message, remediation); }
    public InferenceException(String code, String message, Throwable cause) { super(code, message, cause); }
    public InferenceException(String code, String message, String remediation, Throwable cause) {
        super(code, message, cause);
        remediate(remediation);
    }
}
