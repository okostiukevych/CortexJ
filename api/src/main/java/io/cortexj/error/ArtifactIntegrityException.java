package io.cortexj.error;

public class ArtifactIntegrityException extends CortexJException {
    public ArtifactIntegrityException(String code, String message) { super(code, message); }
    public ArtifactIntegrityException(String code, String message, String remediation) { super(code, message, remediation); }
    public ArtifactIntegrityException(String code, String message, Throwable cause) { super(code, message, cause); }
}
