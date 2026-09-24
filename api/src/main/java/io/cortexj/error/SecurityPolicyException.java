package io.cortexj.error;

public class SecurityPolicyException extends CortexJException {
    public SecurityPolicyException(String code, String message) { super(code, message); }
    public SecurityPolicyException(String code, String message, String remediation) { super(code, message, remediation); }
    public SecurityPolicyException(String code, String message, Throwable cause) { super(code, message, cause); }
}
