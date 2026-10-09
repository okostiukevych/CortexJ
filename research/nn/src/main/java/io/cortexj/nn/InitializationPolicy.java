package io.cortexj.nn;

/**
 * One initialization policy: the value for a parameter slot. Pure
 * math; deterministic implementations are preferred for
 * reproducibility.
 */
public interface InitializationPolicy {

    double valueAt(int index);
}
