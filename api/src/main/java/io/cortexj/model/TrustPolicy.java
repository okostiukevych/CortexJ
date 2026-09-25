package io.cortexj.model;

public enum TrustPolicy {

    /** Only models explicitly marked trusted may load; anything else fails with an actionable error. */
    TRUSTED_ONLY,

    /** Untrusted models load, but the platform records a warning event (default). */
    ALLOW_UNSIGNED_WITH_WARNING,

    /** Trust decisions are delegated to user-registered execution policies. */
    CUSTOM
}
