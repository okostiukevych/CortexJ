package io.cortexj.autograd;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Gradient hooks (ADR-026): per-variable callbacks fired after the
 * gradient is accumulated. Registration is explicit; a throwing hook
 * fails the backward typed, never silently.
 */
public final class GradientHooks {

    private final List<Hook> hooks = new ArrayList<>();

    /**
     * Registers a hook on one variable; returns the hook for removal.
     */
    public Hook onGradient(Variable variable, Consumer<double[]> callback) {
        Hook hook = new Hook(variable, callback);
        hooks.add(hook);
        variable.addHook(hook);
        return hook;
    }

    public void remove(Hook hook) {
        hooks.remove(hook);
        hook.variable().removeHook(hook);
    }

    public int size() {
        return hooks.size();
    }

    /**
     * One registered hook: variable + callback, removable.
     */
    public static final class Hook {

        private final Variable variable;
        private final Consumer<double[]> callback;
        private boolean removed;

        Hook(Variable variable, Consumer<double[]> callback) {
            this.variable = variable;
            this.callback = callback;
        }

        public Variable variable() {
            return variable;
        }

        void fire(double[] gradient) {
            if (!removed) {
                callback.accept(gradient);
            }
        }

        void remove() {
            removed = true;
        }
    }
}
