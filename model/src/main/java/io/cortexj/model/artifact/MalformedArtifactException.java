package io.cortexj.model.artifact;

/**
 * Thrown when an artifact header is malformed, truncated or exceeds sane
 * structural limits (GitHub #62). A RuntimeException subclass of
 * {@link IllegalArgumentException} so planning-time inspection keeps its
 * skip-on-failure semantics.
 */
public class MalformedArtifactException extends IllegalArgumentException {

    public MalformedArtifactException(String message) {
        super(message);
    }

    public MalformedArtifactException(String message, Throwable cause) {
        super(message, cause);
    }
}
