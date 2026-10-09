package io.cortexj.nn;

import io.cortexj.autograd.Autograd;
import io.cortexj.autograd.Variable;
import io.cortexj.tensor.DType;
import io.cortexj.tensor.Device;
import io.cortexj.tensor.Shape;
import io.cortexj.tensor.TensorBackend;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Base module: registration of parameters/submodules, train/eval mode,
 * state dict, serialization, inspection and notebook-friendly repr.
 * Subclasses register in the constructor and implement forward.
 */
public abstract class TensorModule implements Module {

    private final List<Variable> parameters = new ArrayList<Variable>();
    private final List<Module> submodules = new ArrayList<Module>();
    private final Map<String, Variable> namedParameters = new LinkedHashMap<>();
    private boolean training = true;

    /**
     * Registers a parameter under a path-qualified name.
     */
    protected Variable register(String name, Variable parameter) {
        parameters.add(parameter);
        namedParameters.put(name, parameter);
        return parameter;
    }

    /**
     * Registers a submodule under a path-qualified name.
     */
    protected Module register(String name, Module submodule) {
        submodules.add(submodule);
        return submodule;
    }

    @Override
    public List<Variable> parameters() {
        return Collections.unmodifiableList(parameters);
    }

    @Override
    public List<Module> submodules() {
        return Collections.unmodifiableList(submodules);
    }

    @Override
    public boolean training() {
        return training;
    }

    @Override
    public void training(boolean training) {
        this.training = training;
        for (Module submodule : submodules) {
            submodule.training(training);
        }
    }

    @Override
    public Map<String, double[]> stateDict() {
        Map<String, double[]> state = new LinkedHashMap<String, double[]>();
        for (Map.Entry<String, Variable> entry : namedParameters.entrySet()) {
            state.put(entry.getKey(), entry.getValue().data().toArray());
        }
        for (Module submodule : submodules) {
            if (submodule instanceof TensorModule tensorModule) {
                for (Map.Entry<String, double[]> entry : tensorModule.stateDict().entrySet()) {
                    // nested names are already path-qualified by the
                    // submodule's own registration names
                    state.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return state;
    }

    @Override
    public void loadStateDict(Map<String, double[]> state) {
        for (Map.Entry<String, Variable> entry : namedParameters.entrySet()) {
            double[] values = state.get(entry.getKey());
            if (values == null) {
                throw new IllegalArgumentException("state dict is missing parameter: " + entry.getKey());
            }
            Variable parameter = entry.getValue();
            if (values.length != (int) parameter.shape().size()) {
                throw new IllegalArgumentException("state dict parameter " + entry.getKey()
                        + " has " + values.length + " values, expected " + parameter.shape().size());
            }
            parameter.loadValues(values);
        }
    }

    /**
     * Notebook-friendly representation: the module tree with parameter
     * counts.
     */
    @Override
    public String repr() {
        StringBuilder tree = new StringBuilder();
        buildRepr(getClass().getSimpleName(), this, 0, tree);
        return tree.toString();
    }

    private static void buildRepr(String name, Module module, int depth, StringBuilder tree) {
        tree.append("  ".repeat(depth)).append(name)
                .append(" (").append(module.parameters().size()).append(" params)\n");
        for (Module submodule : module.submodules()) {
            buildRepr(submodule.getClass().getSimpleName(), submodule, depth + 1, tree);
        }
    }
}
