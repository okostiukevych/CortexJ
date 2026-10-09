package io.cortexj.nn;

import io.cortexj.autograd.Autograd;
import io.cortexj.autograd.Variable;
import io.cortexj.tensor.Shape;

import java.util.Random;

/**
 * Initialization policies for parameters (ADR-026): zeros, constant,
 * uniform and Xavier/Glorot scaling. Pure math, deterministic per seed.
 */
public final class Initialization {

    private Initialization() {
    }

    public static InitializationPolicy zeros() {
        return value -> 0.0;
    }

    public static InitializationPolicy constant(double value) {
        return ignored -> value;
    }

    /**
     * Uniform in [-bound, bound), deterministic per seed.
     */
    public static InitializationPolicy uniform(double bound, long seed) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive");
        }
        Random random = new Random(seed);
        return ignored -> (random.nextDouble() * 2 - 1) * bound;
    }

    /**
     * Xavier/Glorot: uniform bound sqrt(6 / (fanIn + fanOut)).
     */
    public static InitializationPolicy xavier(long fanIn, long fanOut, long seed) {
        if (fanIn < 1 || fanOut < 1) {
            throw new IllegalArgumentException("fan sizes must be positive");
        }
        return uniform(Math.sqrt(6.0 / (fanIn + fanOut)), seed);
    }

    /**
     * Creates a parameter of the given shape, filled by the policy.
     */
    public static Variable parameter(Autograd autograd, String name, io.cortexj.nn.TensorModule module,
                                     Shape shape, InitializationPolicy policy) {
        double[] values = new double[(int) shape.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = policy.valueAt(i);
        }
        return module.register(name, autograd.variable(values, shape));
    }
}
