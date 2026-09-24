package io.cortexj.error;

public class ArtifactResolutionException extends CortexJException {
    public ArtifactResolutionException(String code, String message) { super(code, message); }
    public ArtifactResolutionException(String code, String message, String remediation) { super(code, message, remediation); }
    public ArtifactResolutionException(String code, String message, Throwable cause) { super(code, message, cause); }
}
