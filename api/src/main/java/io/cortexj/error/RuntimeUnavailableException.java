package io.cortexj.error;

public class RuntimeUnavailableException extends CortexJException {
    public RuntimeUnavailableException(String code, String message) { super(code, message); }
    public RuntimeUnavailableException(String code, String message, String remediation) { super(code, message, remediation); }
    public RuntimeUnavailableException(String code, String message, Throwable cause) { super(code, message, cause); }
    public RuntimeUnavailableException(String code, String message, String remediation, Throwable cause) {
        super(code, message, cause);
        remediate(remediation);
    }
}
