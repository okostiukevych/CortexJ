package io.cortexj.error;

import io.cortexj.ErrorCode;

public class ModelResolutionException extends CortexJException {
    public ModelResolutionException(String code, String message) { super(code, message); }
    public ModelResolutionException(String code, String message, String remediation) { super(code, message, remediation); }
    public ModelResolutionException(String code, String message, Throwable cause) { super(code, message, cause); }
}
