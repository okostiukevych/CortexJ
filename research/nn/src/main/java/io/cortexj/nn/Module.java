package io.cortexj.nn;

import java.util.List;
import java.util.Map;

/**
 * Neural module contract: forward computation plus registration of
 * parameters and submodules. A researcher implements a new architecture
 * entirely in JVM source without modifying the framework (ADR-026).
 */
public interface Module {

    /**
     * Forward computation over the autograd graph.
     */
    Object forward(Object input);

    /**
     * The registered parameters (leaf variables), in registration order.
     */
    List<io.cortexj.autograd.Variable> parameters();

    /**
     * The nested submodules, in registration order.
     */
    List<Module> submodules();

    /**
     * train/eval mode: modules with stateful behavior (dropout,
     * normalization) must honor it.
     */
    boolean training();

    void training(boolean training);

    /**
     * State dict equivalent: flat parameter name to values. Names are
     * path-qualified ("layer.weight") for nested modules.
     */
    Map<String, double[]> stateDict();

    /**
     * Loads a state dict produced by {@link #stateDict()}; unknown or
     * missing names fail typed, never silently.
     */
    void loadStateDict(Map<String, double[]> state);

    /**
     * Inspectable module tree for notebooks and debugging.
     */
    String repr();
}
