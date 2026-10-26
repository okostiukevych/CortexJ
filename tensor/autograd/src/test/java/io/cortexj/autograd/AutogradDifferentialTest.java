package io.cortexj.autograd;

import io.cortexj.tensor.DType;
import io.cortexj.tensor.Device;
import io.cortexj.tensor.Shape;
import io.cortexj.tensor.Tensor;
import io.cortexj.tensor.TensorBackend;
import io.cortexj.tensor.cpu.CpuBackend;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Differential suite (ADR-026): autograd gradients compared against
 * finite-difference references within declared tolerance. Trusted
 * reference first.
 */
class AutogradDifferentialTest {

    private static final TensorBackend BACKEND = new CpuBackend();
    private static final double TOLERANCE = 1e-3;

    @Test
    void simpleLinearGradientsMatchFiniteDifferences() {
        Autograd autograd = new Autograd(BACKEND);
        Variable w = autograd.variable(new double[]{0.5, -1.0, 2.0}, Shape.of(3));
        Variable x = autograd.constant(new double[]{1.0, 2.0, -0.5}, Shape.of(3));
        Variable y = autograd.mul(w, x);
        Variable loss = autograd.sum(y);
        autograd.backward(loss);

        double[] grad = w.grad();
        // d(loss)/dw_i = x_i
        assertEquals(1.0, grad[0], TOLERANCE);
        assertEquals(2.0, grad[1], TOLERANCE);
        assertEquals(-0.5, grad[2], TOLERANCE);
        // finite-difference cross-check
        assertFiniteDifference(values -> {
            double total = 0;
            for (int i = 0; i < values.length; i++) {
                total += values[i] * x.data().toArray()[i];
            }
            return total;
        }, w, grad);
    }

    @Test
    void mlpGradientsMatchFiniteDifferences() {
        // 1-2-1 MLP: h = relu(W1 x + b1), y = W2 h + b2, loss = mean(y*y)
        Autograd autograd = new Autograd(BACKEND);
        Variable w1 = autograd.variable(new double[]{0.3, -0.2}, Shape.of(2));
        Variable b1 = autograd.variable(new double[]{0.1, 0.1}, Shape.of(2));
        Variable w2 = autograd.variable(new double[]{0.5, -0.4}, Shape.of(2));
        Variable x = autograd.constant(new double[]{1.0, 1.0}, Shape.of(2));

        Variable pre = autograd.mul(w1, x);
        Variable preBiased = autograd.add(pre, b1);
        Variable hidden = autograd.relu(preBiased);
        Variable output = autograd.mul(w2, hidden);
        Variable loss = autograd.mean(output);
        autograd.backward(loss);

        double[] w1Grad = w1.grad();
        double[] w2Grad = w2.grad();
        double[] b1Grad = b1.grad();
        assertEquals(2, w1Grad.length);
        assertEquals(2, w2Grad.length);
        assertEquals(2, b1Grad.length);
        // finite-difference cross-check on w2
        assertFiniteDifference(values -> {
            double h0 = Math.max(0, 0.3 + 0.1);
            double h1 = Math.max(0, -0.2 + 0.1);
            return (values[0] * h0 + values[1] * h1) / 2.0;
        }, w2, w2Grad);
        // finite-difference cross-check on w1
        double w2a = 0.5;
        double w2b = -0.4;
        assertFiniteDifference(values -> {
            double h0 = Math.max(0, values[0] * 1.0 + 0.1);
            double h1 = Math.max(0, values[1] * 1.0 + 0.1);
            return (w2a * h0 + w2b * h1) / 2.0;
        }, w1, w1Grad);
        assertFiniteDifference(values -> {
            double h0 = Math.max(0, 0.3 + values[0]);
            double h1 = Math.max(0, -0.2 + values[1]);
            return (w2a * h0 + w2b * h1) / 2.0;
        }, b1, b1Grad);
    }

    @Test
    void matmulGradientsMatchFiniteDifferences() {
        Autograd autograd = new Autograd(BACKEND);
        Variable a = autograd.variable(new double[]{1.0, 2.0, 3.0, 4.0}, Shape.of(2, 2));
        Variable b = autograd.variable(new double[]{0.5, -0.5, 1.0, 0.0}, Shape.of(2, 2));
        Variable product = autograd.matmul(a, b);
        Variable loss = autograd.sum(product);
        autograd.backward(loss);

        // d(loss)/dA = ones(2x2) x B^T = row sums of B
        double[] aGrad = a.grad();
        double bRowSum0 = 0.5 + (-0.5);
        double bRowSum1 = 1.0 + 0.0;
        assertEquals(bRowSum0, aGrad[0], TOLERANCE);
        assertEquals(bRowSum1, aGrad[1], TOLERANCE);
        assertEquals(bRowSum0, aGrad[2], TOLERANCE);
        assertEquals(bRowSum1, aGrad[3], TOLERANCE);
        // finite-difference cross-check
        assertFiniteDifference(values -> {
            double total = 0;
            for (int r = 0; r < 2; r++) {
                for (int c = 0; c < 2; c++) {
                    double cell = 0;
                    for (int k = 0; k < 2; k++) {
                        cell += values[r * 2 + k] * b.data().toArray()[k * 2 + c];
                    }
                    total += cell;
                }
            }
            return total;
        }, a, aGrad);
    }

    @Test
    void gradientAccumulationSumsAcrossBackwardCalls() {
        Autograd autograd = new Autograd(BACKEND);
        Variable w = autograd.variable(new double[]{1.0, 2.0}, Shape.of(2));
        Variable x = autograd.constant(new double[]{1.0, 1.0}, Shape.of(2));
        Variable y = autograd.mul(w, x);
        Variable loss = autograd.sum(y);
        autograd.backward(loss);
        double[] firstGrad = w.grad();
        assertEquals(1.0, firstGrad[0], TOLERANCE);
        // a second tracked path accumulates into the same variable
        Variable y2 = autograd.mul(w, x);
        Variable loss2 = autograd.sum(y2);
        autograd.backward(loss2);
        double[] accumulated = w.grad();
        assertEquals(2.0, accumulated[0], TOLERANCE);
        assertEquals(2.0, accumulated[1], TOLERANCE);
    }

    @Test
    void detachAndNoGradSkipRecording() {
        Autograd autograd = new Autograd(BACKEND);
        Variable w = autograd.variable(new double[]{1.0, 2.0}, Shape.of(2));
        Variable x = autograd.constant(new double[]{1.0, 1.0}, Shape.of(2));
        Variable detached = autograd.detach(w);
        assertTrue(!detached.requiresGradient());
        assertTrue(detached.grad() == null);
        // no-grad ops skip the tape
        int before = autograd.tapeSize();
        Variable frozen = autograd.noGrad(() -> autograd.mul(w, x));
        assertEquals(before, autograd.tapeSize());
        // frozen output does not require gradient
        assertTrue(!frozen.requiresGradient());
    }

    @Test
    void customBackwardRuleRuns() {
        Autograd autograd = new Autograd(BACKEND);
        Variable w = autograd.variable(new double[]{1.0}, Shape.of(1));
        Variable x = autograd.variable(new double[]{2.0}, Shape.of(1));
        // custom op: output = w * x, but backward multiplies the gradient by 3
        Tensor forward = BACKEND.mul(w.data(), x.data());
        Variable output = new Variable(forward, true);
        autograd.record(output, java.util.List.of(w), gradOutput -> new double[][]{
                new double[]{gradOutput[0] * 3.0}
        });
        Variable loss = autograd.sum(output);
        autograd.backward(loss);
        // output gradient from sum is 1; the rule multiplies by 3
        assertEquals(3.0, w.grad()[0], TOLERANCE);
    }

    @Test
    void backwardRequiresScalarLoss() {
        Autograd autograd = new Autograd(BACKEND);
        Variable w = autograd.variable(new double[]{1.0, 2.0}, Shape.of(2));
        assertThrows(IllegalArgumentException.class, () -> autograd.backward(w));
    }

    @Test
    void diamondDagBackwardComputesExactGradient() {
        Autograd autograd = new Autograd(BACKEND);
        // y = x * x; z = y + y; loss = sum(z) = 2 * sum(x^2) => d(loss)/dx = 4 * x
        Variable x = autograd.variable(new double[]{1.5, -2.0, 3.0}, Shape.of(3));
        Variable y = autograd.mul(x, x);
        Variable z = autograd.add(y, y);
        Variable loss = autograd.sum(z);
        autograd.backward(loss);
        double[] grad = x.grad();
        assertEquals(6.0, grad[0], TOLERANCE);
        assertEquals(-8.0, grad[1], TOLERANCE);
        assertEquals(12.0, grad[2], TOLERANCE);
    }

    @Test
    void repeatedBackwardDoesNotDoubleCountIntermediateGradients() {
        Autograd autograd = new Autograd(BACKEND);
        Variable x = autograd.variable(new double[]{2.0, 3.0}, Shape.of(2));
        Variable y = autograd.mul(x, x);
        Variable loss = autograd.sum(y);
        autograd.backward(loss);
        assertEquals(4.0, x.grad()[0], TOLERANCE);
        assertEquals(6.0, x.grad()[1], TOLERANCE);

        // Calling backward(loss) a second time accumulates into leaf x (+4.0, +6.0 => 8.0, 12.0),
        // NOT 12.0, 18.0 which would happen if intermediate y kept its stale gradient.
        autograd.backward(loss);
        assertEquals(8.0, x.grad()[0], TOLERANCE);
        assertEquals(12.0, x.grad()[1], TOLERANCE);
    }

    @Test
    void broadcastedAddAndMulBackwardReduceToOperandShapes() {
        Autograd autograd = new Autograd(BACKEND);
        Variable a = autograd.variable(new double[]{1.0, 2.0, 3.0, 4.0, 5.0, 6.0}, Shape.of(2, 3));
        Variable b = autograd.variable(new double[]{10.0, 20.0, 30.0}, Shape.of(3));
        Variable sum = autograd.add(a, b);
        Variable prod = autograd.mul(sum, b);
        Variable loss = autograd.sum(prod);
        autograd.backward(loss);

        // loss = sum_{r,c} (a_{r,c} + b_c) * b_c
        // d(loss)/da_{r,c} = b_c
        double[] aGrad = a.grad();
        assertEquals(6, aGrad.length);
        assertEquals(10.0, aGrad[0], TOLERANCE);
        assertEquals(20.0, aGrad[1], TOLERANCE);
        assertEquals(30.0, aGrad[2], TOLERANCE);
        assertEquals(10.0, aGrad[3], TOLERANCE);
        assertEquals(20.0, aGrad[4], TOLERANCE);
        assertEquals(30.0, aGrad[5], TOLERANCE);

        // d(loss)/db_c = sum_r (a_{r,c} + 2 * b_c)
        double[] bGrad = b.grad();
        assertEquals(3, bGrad.length);
        assertEquals((1.0 + 20.0) + (4.0 + 20.0), bGrad[0], TOLERANCE);
        assertEquals((2.0 + 40.0) + (5.0 + 40.0), bGrad[1], TOLERANCE);
        assertEquals((3.0 + 60.0) + (6.0 + 60.0), bGrad[2], TOLERANCE);
    }

    @Test
    void batchedSoftmaxForwardAndBackwardArePerRow() {
        Autograd autograd = new Autograd(BACKEND);
        Variable x = autograd.variable(new double[]{1.0, 2.0, 3.0, -1.0, 0.0, 1.0}, Shape.of(2, 3));
        Variable weights = autograd.constant(new double[]{0.5, -1.0, 2.0, 1.5, 0.0, -0.5}, Shape.of(2, 3));
        Variable probs = autograd.softmax(x);
        // Each row of probs must sum to 1.0
        double row0 = probs.data().get(0, 0) + probs.data().get(0, 1) + probs.data().get(0, 2);
        double row1 = probs.data().get(1, 0) + probs.data().get(1, 1) + probs.data().get(1, 2);
        assertEquals(1.0, row0, TOLERANCE);
        assertEquals(1.0, row1, TOLERANCE);

        Variable weighted = autograd.mul(probs, weights);
        Variable loss = autograd.sum(weighted);
        autograd.backward(loss);

        double[] wArr = weights.data().toArray();
        assertFiniteDifference(values -> {
            double total = 0.0;
            for (int r = 0; r < 2; r++) {
                double max = Math.max(values[r * 3], Math.max(values[r * 3 + 1], values[r * 3 + 2]));
                double s = 0.0;
                double[] e = new double[3];
                for (int c = 0; c < 3; c++) {
                    e[c] = Math.exp(values[r * 3 + c] - max);
                    s += e[c];
                }
                for (int c = 0; c < 3; c++) {
                    total += (e[c] / s) * wArr[r * 3 + c];
                }
            }
            return total;
        }, x, x.grad());
    }

    @Test
    void noGradIsThreadLocalAcrossConcurrentThreads() throws Exception {
        Autograd autograd = new Autograd(BACKEND);
        Variable w = autograd.variable(new double[]{1.0, 2.0}, Shape.of(2));
        Variable x = autograd.constant(new double[]{3.0, 4.0}, Shape.of(2));
        java.util.concurrent.CountDownLatch insideNoGrad = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch ReleaseNoGrad = new java.util.concurrent.CountDownLatch(1);

        Thread t1 = new Thread(() -> autograd.noGrad(() -> {
            insideNoGrad.countDown();
            try {
                ReleaseNoGrad.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return autograd.mul(w, x);
        }));
        t1.start();
        insideNoGrad.await();
        // On main thread, recording must still be enabled while t1 is inside noGrad
        Variable tracked = autograd.mul(w, x);
        ReleaseNoGrad.countDown();
        t1.join();
        assertTrue(tracked.requiresGradient());
    }

    private static Variable broadcast(Autograd autograd, double[] values, int times) {
        double[] broadcast = new double[values.length * times];
        for (int i = 0; i < times; i++) {
            System.arraycopy(values, 0, broadcast, i * values.length, values.length);
        }
        return autograd.constant(broadcast, Shape.of(times, 1));
    }

    /**
     * Trusted reference: central finite differences on the parameters.
     */
    private static void assertFiniteDifference(java.util.function.Function<double[], Double> function,
                                               Variable parameter, double[] expected) {
        double epsilon = 1e-5;
        double[] values = parameter.data().toArray();
        double[] numerical = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            double[] plus = values.clone();
            double[] minus = values.clone();
            plus[i] += epsilon;
            minus[i] -= epsilon;
            numerical[i] = (function.apply(plus) - function.apply(minus)) / (2 * epsilon);
        }
        assertEquals(values.length, expected.length);
        for (int i = 0; i < values.length; i++) {
            assertTrue(Math.abs(numerical[i] - expected[i]) < TOLERANCE,
                    "gradient " + i + ": numerical " + numerical[i]
                            + " vs autograd " + expected[i]);
        }
    }
}
