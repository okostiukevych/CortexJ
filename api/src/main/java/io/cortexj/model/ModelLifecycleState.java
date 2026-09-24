package io.cortexj.model;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public enum ModelLifecycleState {

    DISCOVERED,
    RESOLVING,
    DOWNLOADING,
    PREPARING,
    LOADING,
    READY,
    DEGRADED,
    CLOSING,
    CLOSED,
    FAILED;

    private static final Set<ModelLifecycleState> TERMINAL = Collections.unmodifiableSet(
            EnumSet.of(CLOSED, FAILED));

    private static final Set<ModelLifecycleState> ACTIVE = Collections.unmodifiableSet(
            EnumSet.range(DISCOVERED, DEGRADED));

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public boolean isActive() {
        return ACTIVE.contains(this);
    }

    public boolean canTransitionTo(ModelLifecycleState next) {
        if (this == next) {
            return true;
        }
        if (isTerminal()) {
            return false;
        }
        switch (this) {
            case DISCOVERED:
                return next == RESOLVING || next == FAILED;
            case RESOLVING:
                return next == DOWNLOADING || next == PREPARING || next == LOADING || next == READY || next == FAILED;
            case DOWNLOADING:
                return next == PREPARING || next == LOADING || next == FAILED;
            case PREPARING:
                return next == LOADING || next == FAILED;
            case LOADING:
                return next == READY || next == FAILED;
            case READY:
                return next == DEGRADED || next == CLOSING || next == FAILED;
            case DEGRADED:
                return next == READY || next == CLOSING || next == FAILED;
            case CLOSING:
                return next == CLOSED;
            default:
                return false;
        }
    }
}
