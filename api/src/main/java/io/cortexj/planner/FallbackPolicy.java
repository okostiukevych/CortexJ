package io.cortexj.planner;

public enum FallbackPolicy {
    DISABLED,
    LOAD_TIME_ONLY,
    /**
     * Declared but not implemented (audit 2026-10-07, #147): inference-time
     * fallback does not exist - {@code generate}/{@code execute} throw
     * immediately on failure and never re-plan. Selecting this value
     * currently behaves exactly like {@link #LOAD_TIME_ONLY}.
     *
     * @deprecated use {@link #LOAD_TIME_ONLY} until inference-time fallback
     *             is implemented; kept for API stability so existing
     *             configuration keeps loading.
     */
    @Deprecated
    INFERENCE_SAFE,
    CUSTOM
}
