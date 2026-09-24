package io.cortexj.error;

public class InsufficientMemoryException extends CortexJException {
    public InsufficientMemoryException(String code, String message) { super(code, message); }
    public InsufficientMemoryException(String code, String message, String remediation) { super(code, message, remediation); }
    public InsufficientMemoryException(String code, String message, Throwable cause) { super(code, message, cause); }
    public InsufficientMemoryException(String code, String message, String remediation, Throwable cause) {
        super(code, message, cause);
        remediate(remediation);
    }
}
