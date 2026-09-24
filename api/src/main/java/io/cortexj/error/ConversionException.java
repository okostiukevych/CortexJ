package io.cortexj.error;

public class ConversionException extends CortexJException {
    public ConversionException(String code, String message) { super(code, message); }
    public ConversionException(String code, String message, String remediation) { super(code, message, remediation); }
    public ConversionException(String code, String message, Throwable cause) { super(code, message, cause); }
    public ConversionException(String code, String message, String remediation, Throwable cause) {
        super(code, message, cause);
        remediate(remediation);
    }
}
