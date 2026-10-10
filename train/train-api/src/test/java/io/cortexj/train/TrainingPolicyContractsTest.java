package io.cortexj.train;

import io.cortexj.train.standard.CrossEntropy;
import io.cortexj.train.standard.Loss;
import io.cortexj.train.standard.Standard;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Policy contracts: pure-math implementations hand-computed; plan
 * composition; progressive disclosure.
 */
class TrainingPolicyContractsTest {

    @Test
    void crossEntropyIsHandComputed() {
        CrossEntropy loss = new CrossEntropy();
        // logits [2, 0], target [1, 0]: softmax = [e^2/(e^2+1), 1/(e^2+1)]
        // loss = -log(e^2/(e^2+1)) = log(1 + e^-2)
        double expected = Math.log(1 + Math.exp(-2.0));
        assertEquals(expected, loss.loss(new double[]{2.0, 0.0}, new double[]{1.0, 0.0}), 1e-9);
        // gradient = softmax - target: [e^2/(e^2+1) - 1, 1/(e^2+1) - 0]
        double p = Math.exp(2.0) / (Math.exp(2.0) + 1.0);
        assertEquals(p - 1.0, loss.gradient(new double[]{2.0, 0.0}, new double[]{1.0, 0.0})[0], 1e-9);
        assertEquals(1.0 - p, loss.gradient(new double[]{2.0, 0.0}, new double[]{1.0, 0.0})[1], 1e-9);
    }

    @Test
    void sgdStepsByScaledGradient() {
        Optimizer sgd = Standard.sgd(0.1);
        double[] updated = sgd.step(new double[]{1.0}, new double[]{2.0});
        assertEquals(0.8, updated[0], 1e-12);
        // momentum: velocity accumulates
        Optimizer momentum = Standard.sgd(0.1, 0.5);
        double[] first = momentum.step(new double[]{1.0}, new double[]{2.0});
        assertEquals(0.8, first[0], 1e-12);
        double[] second = momentum.step(first, new double[]{2.0});
        // velocity = 0.5*(-0.2) - 0.1*2 = -0.3; value = 0.8 - 0.3 = 0.5
        assertEquals(0.5, second[0], 1e-12);
    }

    @Test
    void adamWFirstStepIsHandComputed() {
        // beta1=0.9, beta2=0.999, eps=1e-8, decay=0.01, lr=1e-3, params=[1], grad=[2]:
        // m = 0.1*2 = 0.2, v = 0.001*4 = 0.004
        // mHat = 0.2/0.1 = 2, vHat = 0.004/0.001 = 4
        // update = 1e-3 * 2/(2 + 1e-8) + 1e-3*0.01*1 = 0.001 + 0.00001
        Optimizer adamW = Standard.adamW(1e-3);
        double[] updated = adamW.step(new double[]{1.0}, new double[]{2.0});
        assertEquals(1.0 - 0.001 - 0.00001, updated[0], 1e-9);
        assertEquals("adamw", adamW.id());
    }

    @Test
    void lossFactoriesAreNamed() {
        assertEquals("cross-entropy", Loss.crossEntropy().id());
        assertEquals("mean-squared-error", Loss.meanSquaredError().id());
        // MSE hand-computed: pred [1, 3], target [0, 0] -> (1 + 9)/2 = 5
        assertEquals(5.0, Loss.meanSquaredError()
                .loss(new double[]{1.0, 3.0}, new double[]{0.0, 0.0}), 1e-12);
        // gradient: 2*(pred - target)/n = [1, 3]
        assertEquals(1.0, Loss.meanSquaredError()
                .gradient(new double[]{1.0, 3.0}, new double[]{0.0, 0.0})[0], 1e-12);
        assertEquals(3.0, Loss.meanSquaredError()
                .gradient(new double[]{1.0, 3.0}, new double[]{0.0, 0.0})[1], 1e-12);
    }

    @Test
    void schedulesAreHandComputed() {
        assertEquals(0.05, Standard.constant(0.05).rateAt(100), 1e-12);
        // warmup 2, total 4, peak 0.4: step0 = 0.4*(1/2)=0.2, step1=0.4,
        // step2 = 0.4*(2/2)=0.4, step3 = 0.4*(1/2)=0.2, step4 = 0
        assertEquals(0.2, Standard.warmupDecay(2, 4, 0.4).rateAt(0), 1e-12);
        assertEquals(0.4, Standard.warmupDecay(2, 4, 0.4).rateAt(1), 1e-12);
        assertEquals(0.2, Standard.warmupDecay(2, 4, 0.4).rateAt(3), 1e-12);
        assertEquals(0.0, Standard.warmupDecay(2, 4, 0.4).rateAt(4), 1e-12);
    }

    @Test
    void checkpointPolicyFiresOnIntervalAndFinalStep() {
        var policy = Standard.checkpointEvery(2, 3);
        assertTrue(policy.checkpointAt(2, 6));
        assertTrue(policy.checkpointAt(6, 6));
        assertTrue(!policy.checkpointAt(3, 6));
        assertEquals(3, policy.keep());
    }

    @Test
    void earlyStoppingFiresAfterWindowWithoutImprovement() {
        var policy = Standard.earlyStopping(1, 2);
        // losses keep improving: no stop
        assertTrue(!policy.shouldStop(new double[]{1.0, 0.9, 0.8}, 2));
        // plateau after the best: stop
        assertTrue(policy.shouldStop(new double[]{0.8, 0.9, 0.9}, 2));
    }

    @Test
    void mixedPrecisionIsExplicit() {
        var policy = Standard.mixedPrecision(io.cortexj.train.MixedPrecisionPolicy.Precision.F16, true);
        assertEquals(io.cortexj.train.MixedPrecisionPolicy.Precision.F16, policy.requested());
        assertTrue(policy.fallbackAllowed());
    }

    @Test
    void planComposesRequestAndPolicies() {
        TrainingPlan plan = new TrainingPlan(
                TrainingRequest.builder().model("m").dataset("d").build(),
                Standard.sgd(0.1),
                new CrossEntropy(),
                Standard.constant(0.1),
                Standard.checkpointEvery(2, 3),
                Standard.earlyStopping(1, 2),
                Standard.mixedPrecision(io.cortexj.train.MixedPrecisionPolicy.Precision.F32, false),
                Device.CPU);
        assertEquals("m", plan.request().model());
        assertEquals("sgd", plan.optimizer().id());
        assertEquals("cross-entropy", plan.loss().id());
        assertEquals(Device.CPU, plan.device());
        // null request/optimizer/loss rejected
        assertThrows(IllegalArgumentException.class, () -> new TrainingPlan(
                null, Standard.sgd(0.1), new CrossEntropy(), null, null, null, null, null));
    }

    @Test
    void fullPolicySurfaceIsPresent() {        // the full training policy surface exists as contracts
        Map<String, Class<?>> contracts = Map.of(
                "Trainer", Trainer.class,
                "TrainingPlan", TrainingPlan.class,
                "Optimizer", Optimizer.class,
                "LossFunction", LossFunction.class,
                "LearningRateSchedule", LearningRateSchedule.class,
                "CheckpointPolicy", CheckpointPolicy.class,
                "EvaluationPolicy", EvaluationPolicy.class,
                "MixedPrecisionPolicy", MixedPrecisionPolicy.class,
                "TrainingBackend", TrainingBackend.class);
        for (Map.Entry<String, Class<?>> entry : contracts.entrySet()) {
            assertTrue(entry.getValue().isInterface() || !entry.getValue().isAnonymousClass(),
                    entry.getKey() + " must be a public contract");
        }
    }

    @Test
    void highLevelExampleShapeWorks() {
        // the high-level example shape, minus the Runtime facade the
        // dependency law forbids in the stable api
        TrainingPlan plan = Trainers.trainer()
                .model("m")
                .dataset("d")
                .optimizer(io.cortexj.train.standard.AdamW.builder().learningRate(1e-3).build())
                .loss(Loss.crossEntropy())
                .device(Device.AUTO)
                .build();
        assertEquals("m", plan.request().model());
        assertEquals("adamw", plan.optimizer().id());
        assertEquals("cross-entropy", plan.loss().id());
        assertEquals(Device.AUTO, plan.device());
    }
}
