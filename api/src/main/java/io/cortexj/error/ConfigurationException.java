package io.cortexj.error;

public class ConfigurationException extends CortexJException {
    public ConfigurationException(String code, String message) { super(code, message); }
    public ConfigurationException(String code, String message, String remediation) { super(code, message, remediation); }
    public ConfigurationException(String code, String message, Throwable cause) { super(code, message, cause); }
}
