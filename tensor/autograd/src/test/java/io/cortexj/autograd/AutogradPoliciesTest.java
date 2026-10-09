package io.cortexj.autograd;

import io.cortexj.tensor.Shape;
import io.cortexj.tensor.TensorBackend;
import io.cortexj.tensor.cpu.CpuBackend;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Autograd policies: gradient hooks, checkpointing/recomputation,
 * release of intermediates.
 */
class AutogradPoliciesTest {

    private static final TensorBackend BACKEND = new CpuBackend();

    @Test
    void hooksFireAfterGradientAccumulation() {
        Autograd autograd = new Autograd(BACKEND);
        Variable w = autograd.variable(new double[]{1.0, 2.0}, Shape.of(2));
        GradientHooks hooks = new GradientHooks();
        AtomicReference<double[]> fired = new AtomicReference<>();
        AtomicInteger count = new AtomicInteger();
        GradientHooks.Hook hook = hooks.onGradient(w, gradient -> {
            fired.set(gradient);
            count.incrementAndGet();
        });
        Variable x = autograd.constant(new double[]{1.0, 1.0}, Shape.of(2));
        Variable loss = autograd.sum(autograd.mul(w, x));
        autograd.backward(loss);
        assertEquals(1, count.get());
        assertEquals(1.0, fired.get()[0], 1e-12);
        // hook removal stops firing
        hooks.remove(hook);
        Variable loss2 = autograd.sum(autograd.mul(w, x));
        autograd.backward(loss2);
        assertEquals(1, count.get());
    }

    @Test
    void checkpointRecomputesAndReleasesIntermediates() {
        Autograd autograd = new Autograd(BACKEND);
        Variable w = autograd.variable(new double[]{0.5}, Shape.of(1));
        Variable x = autograd.variable(new double[]{2.0}, Shape.of(1));
        AtomicInteger recomputes = new AtomicInteger();
        // segment: y = w * x, loss = sum(y) — body closes over the same
        // input variables so gradients land in w and x
        Variable loss = AutogradPolicies.checkpoint(autograd, () -> {
            recomputes.incrementAndGet();
            return autograd.sum(autograd.mul(w, x));
        });
        int tapeBefore = autograd.tapeSize();
        autograd.backward(loss);
        // body ran twice: once at forward (no recording), once as the
        // backward recompute; the recomputed intermediates were released
        // (tape back to the pre-backward size)
        assertEquals(2, recomputes.get());
        assertEquals(tapeBefore, autograd.tapeSize());
        // gradients landed in the original variables
        assertEquals(2.0, w.grad()[0], 1e-9);
        assertEquals(0.5, x.grad()[0], 1e-9);
    }

    @Test
    void retentionDiscardOnBackwardReleasesTape() {
        Autograd autograd = new Autograd(BACKEND);
        Variable w = autograd.variable(new double[]{1.0, 2.0}, Shape.of(2));
        Variable x = autograd.constant(new double[]{1.0, 1.0}, Shape.of(2));
        Variable loss = autograd.sum(autograd.mul(w, x));
        assertTrue(autograd.tapeSize() > 0);
        autograd.clearTape();
        assertEquals(0, autograd.tapeSize());
        // backward after a cleared tape: no nodes to walk; the gradient
        // stays at its documented zeros-while-not-run state
        autograd.backward(loss);
        for (double value : w.grad()) {
            assertEquals(0.0, value, 1e-12);
        }
    }

    @Test
    void throwingHookFailsTypedNotSilently() {
        Autograd autograd = new Autograd(BACKEND);
        Variable w = autograd.variable(new double[]{1.0}, Shape.of(1));
        new GradientHooks().onGradient(w, gradient -> {
            throw new IllegalStateException("hook exploded");
        });
        Variable x = autograd.constant(new double[]{2.0}, Shape.of(1));
        Variable loss = autograd.sum(autograd.mul(w, x));
        assertThrows(IllegalStateException.class, () -> autograd.backward(loss));
    }
}
