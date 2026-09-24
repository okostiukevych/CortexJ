package io.cortexj.error;

public class ModelLoadException extends CortexJException {
    public ModelLoadException(String code, String message) { super(code, message); }
    public ModelLoadException(String code, String message, String remediation) { super(code, message, remediation); }
    public ModelLoadException(String code, String message, Throwable cause) { super(code, message, cause); }
}
