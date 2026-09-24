package io.cortexj.error;

public class PlanningException extends CortexJException {
    public PlanningException(String code, String message) { super(code, message); }
    public PlanningException(String code, String message, String remediation) { super(code, message, remediation); }
    public PlanningException(String code, String message, Throwable cause) { super(code, message, cause); }
}
