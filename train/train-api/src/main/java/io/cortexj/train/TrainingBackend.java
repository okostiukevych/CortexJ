package io.cortexj.train;

import io.cortexj.meta.ModelIdentity;

/**
 * SPI for training engines (ADR-021). A backend reports compatibility;
 * the high-level jobs API makes the selection. Engines are wrapped, not
 * reimplemented.
 */
public interface TrainingBackend {

    enum Level { FULL, PARTIAL, UNSUPPORTED }

    /**
     * Compatibility report for one training request: the support level,
     * the honest reason and the memory estimate. Evaluated before any
     * resource allocation.
     */
    final class Support {

        private final Level level;
        private final String reason;
        private final long estimatedMemoryBytes;

        public Support(Level level, String reason, long estimatedMemoryBytes) {
            this.level = level;
            this.reason = reason == null ? "" : reason;
            this.estimatedMemoryBytes = estimatedMemoryBytes;
        }

        public Level level() {
            return level;
        }

        public String reason() {
            return reason;
        }

        public long estimatedMemoryBytes() {
            return estimatedMemoryBytes;
        }

        public boolean isSupported() {
            return level != Level.UNSUPPORTED;
        }
    }

    String id();

    Support evaluate(TrainingRequest request);

    TrainingResult train(TrainingRequest request, Support support);
}
