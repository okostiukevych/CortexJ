package io.cortexj.convert.gguf.internal;

import java.io.IOException;

/**
 * Thrown when a GGUF container violates structural limits (GitHub #62):
 * oversized strings/byte blocks, absurd tensor dimensions or nested metadata
 * arrays. A subclass of {@link IOException} so existing callers keep working.
 */
public class GgufFormatException extends IOException {

    public GgufFormatException(String message) {
        super(message);
    }

    public GgufFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
