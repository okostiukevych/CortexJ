package io.cortexj.train;

/**
 * Mixed-precision behavior: explicit, never implicit. The backend
 * negotiates the actual precision; the policy records the request and
 * the loss-tolerance decision.
 */
public interface MixedPrecisionPolicy {

    String id();

    /**
     * The requested compute dtype.
     */
    Precision requested();

    /**
     * Whether a precision fallback (for example F32 accumulation under
     * F16 compute) is acceptable.
     */
    boolean fallbackAllowed();

    enum Precision {
        F32,
        F16,
        BF16
    }
}
