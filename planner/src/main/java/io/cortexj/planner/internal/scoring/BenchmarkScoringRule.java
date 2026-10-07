package io.cortexj.planner.internal.scoring;

import io.cortexj.planner.ExecutionCandidate;
import io.cortexj.planner.ExecutionRequest;
import io.cortexj.planner.benchmark.BenchmarkHints;
import io.cortexj.planner.internal.PlannerInputs;
import io.cortexj.runtime.DeviceType;
import io.cortexj.runtime.RuntimeDescriptor;

/**
 * Spec 126: locally supplied benchmark hints as an additional deterministic
 * scoring factor. All adjustments are normalized against the file's own
 * extremes (max throughput / min latency / min memory), so the same file
 * always produces the same plan - no learning, no runtime mutation.
 */
public final class BenchmarkScoringRule implements ScoringRules.ScoringRule {

    private static final double THROUGHPUT_BUDGET = 15.0d;
    private static final double LATENCY_BUDGET = 10.0d;
    private static final double MEMORY_BUDGET = 5.0d;

    private final BenchmarkHints hints;

    public BenchmarkScoringRule(BenchmarkHints hints) {
        this.hints = hints;
    }

    @Override
    public String name() {
        return "benchmark-hints";
    }

    @Override
    public void apply(ExecutionCandidate.Builder candidate, ExecutionCandidate snapshot,
                      RuntimeDescriptor runtime, ExecutionRequest request, PlannerInputs inputs) {
        if (hints.isEmpty()) {
            return;
        }
        ExecutionCandidate built = snapshot;
        String runtimeId = built.runtimeId();
        String modelReference = request.model() == null ? "" : request.model().value();
        String quantization = built.quantization() == null ? "" : built.quantization();
        String device = deviceName(built.device());

        Double throughput = hints.best(runtimeId, modelReference, quantization, device,
                BenchmarkHints.METRIC_THROUGHPUT);
        if (throughput != null) {
            double max = hints.maxOf(BenchmarkHints.METRIC_THROUGHPUT);
            if (max > 0.0d) {
                double bonus = THROUGHPUT_BUDGET * (throughput.doubleValue() / max);
                candidate.addScore(bonus);
                candidate.note("benchmark:throughput", throughput + " t/s (+" + round(bonus) + ")");
            }
        }

        Double latency = hints.best(runtimeId, modelReference, quantization, device,
                BenchmarkHints.METRIC_LATENCY);
        if (latency != null) {
            double min = hints.minOf(BenchmarkHints.METRIC_LATENCY);
            if (min > 0.0d) {
                double bonus = LATENCY_BUDGET * (min / latency.doubleValue());
                candidate.addScore(bonus);
                candidate.note("benchmark:latency", latency + " ms (+" + round(bonus) + ")");
            }
        }

        Double memory = hints.best(runtimeId, modelReference, quantization, device,
                BenchmarkHints.METRIC_MEMORY);
        if (memory != null) {
            double min = hints.minOf(BenchmarkHints.METRIC_MEMORY);
            if (min > 0.0d) {
                double bonus = MEMORY_BUDGET * (min / memory.doubleValue());
                candidate.addScore(bonus);
                candidate.note("benchmark:memory", memory + " GB (+" + round(bonus) + ")");
            }
        }
    }

    private static String deviceName(DeviceType device) {
        if (device == null) {
            return "cpu";
        }
        return device.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static String round(double value) {
        return String.valueOf(Math.round(value * 100.0d) / 100.0d);
    }
}
