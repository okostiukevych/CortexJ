package io.cortexj.error;

public class RuntimeCompatibilityException extends CortexJException {
    public RuntimeCompatibilityException(String code, String message) { super(code, message); }
    public RuntimeCompatibilityException(String code, String message, String remediation) { super(code, message, remediation); }
    public RuntimeCompatibilityException(String code, String message, Throwable cause) { super(code, message, cause); }
}
