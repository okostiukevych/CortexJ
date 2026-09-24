package io.cortexj.planner.internal.scoring;

import io.cortexj.model.Quantization;
import io.cortexj.planner.ExecutionCandidate;
import io.cortexj.planner.ExecutionRequest;
import io.cortexj.planner.QualityProfile;
import io.cortexj.planner.internal.PlannerInputs;
import io.cortexj.runtime.DeviceType;
import io.cortexj.runtime.RuntimeDescriptor;

import java.util.ArrayList;
import java.util.List;

public final class ScoringRules {

    public interface ScoringRule {
        String name();

        void apply(ExecutionCandidate.Builder candidate, RuntimeDescriptor runtime, ExecutionRequest request, PlannerInputs inputs);
    }

    private ScoringRules() {
    }

    public static List<ScoringRule> defaults() {
        List<ScoringRule> rules = new ArrayList<ScoringRule>();
        rules.add(new NativeFormatRule());
        rules.add(new LocalCacheRule());
        rules.add(new MemoryHeadroomRule());
        rules.add(new UserPreferenceRule());
        rules.add(new DevicePreferenceRule());
        rules.add(new MaturityRule());
        rules.add(new QualityProfileRule());
        rules.add(new LocalityRule());
        return rules;
    }

    public static final class NativeFormatRule implements ScoringRule {

        @Override
        public String name() {
            return "native-format";
        }

        @Override
        public void apply(ExecutionCandidate.Builder candidate, RuntimeDescriptor runtime, ExecutionRequest request, PlannerInputs inputs) {
            if (candidate.build().format() != io.cortexj.model.ArtifactFormat.UNKNOWN
                    && runtime.formats().contains(candidate.build().format())) {
                candidate.addScore(1.5d);
                candidate.note("nativeFormat", "true");
            }
        }
    }

    public static final class LocalCacheRule implements ScoringRule {

        @Override
        public String name() {
            return "local-cache";
        }

        @Override
        public void apply(ExecutionCandidate.Builder candidate, RuntimeDescriptor runtime, ExecutionRequest request, PlannerInputs inputs) {
            ExecutionCandidate built = candidate.build();
            if (built.artifact() == null) {
                return;
            }
            if (built.requiresDownload()) {
                candidate.addScore(-0.75d);
            } else if ("true".equals(built.notes().get("cached"))) {
                candidate.addScore(1.25d);
            } else if ("true".equals(built.notes().get("localFile"))) {
                candidate.addScore(1.0d);
            }
        }
    }

    public static final class MemoryHeadroomRule implements ScoringRule {

        @Override
        public String name() {
            return "memory-headroom";
        }

        @Override
        public void apply(ExecutionCandidate.Builder candidate, RuntimeDescriptor runtime, ExecutionRequest request, PlannerInputs inputs) {
            ExecutionCandidate built = candidate.build();
            if (!built.memoryEstimate().isKnown()) {
                return;
            }
            long available = inputs.environment.availableMemoryEstimateBytes();
            if (available <= 0) {
                available = inputs.environment.totalPhysicalMemoryBytes();
            }
            if (available <= 0) {
                return;
            }
            long estimated = built.memoryEstimate().totalBytes();
            if (estimated <= available / 2) {
                candidate.addScore(0.75d);
                candidate.note("headroom", "comfortable");
            } else if (estimated <= available) {
                candidate.addScore(-0.5d);
                candidate.note("headroom", "tight");
            }
        }
    }

    public static final class UserPreferenceRule implements ScoringRule {

        @Override
        public String name() {
            return "user-preference";
        }

        @Override
        public void apply(ExecutionCandidate.Builder candidate, RuntimeDescriptor runtime, ExecutionRequest request, PlannerInputs inputs) {
            if (request.preferredRuntime() != null && request.preferredRuntime().equals(runtime.id().value())) {
                candidate.addScore(3.0d);
                candidate.note("preferredRuntime", "true");
            }
            if (request.preferredQuantization() != null && request.preferredQuantization().equals(candidate.build().quantization())) {
                candidate.addScore(1.5d);
                candidate.note("preferredQuantization", "true");
            }
        }
    }

    public static final class DevicePreferenceRule implements ScoringRule {

        @Override
        public String name() {
            return "device-preference";
        }

        @Override
        public void apply(ExecutionCandidate.Builder candidate, RuntimeDescriptor runtime, ExecutionRequest request, PlannerInputs inputs) {
            io.cortexj.planner.DevicePreference preference = request.preferredDevice();
            if (preference == null || preference == io.cortexj.planner.DevicePreference.AUTO) {
                return;
            }
            DeviceType wanted = toDeviceType(preference);
            if (wanted != null && wanted == candidate.build().device()) {
                candidate.addScore(1.25d);
                candidate.note("preferredDevice", "true");
            }
        }

        private static DeviceType toDeviceType(io.cortexj.planner.DevicePreference preference) {
            switch (preference) {
                case CPU: return DeviceType.CPU;
                case GPU: return DeviceType.GPU;
                case NPU: return DeviceType.NPU;
                case REMOTE: return DeviceType.REMOTE;
                default: return null;
            }
        }
    }

    public static final class MaturityRule implements ScoringRule {

        @Override
        public String name() {
            return "maturity";
        }

        @Override
        public void apply(ExecutionCandidate.Builder candidate, RuntimeDescriptor runtime, ExecutionRequest request, PlannerInputs inputs) {
            switch (runtime.maturity()) {
                case STABLE:
                    candidate.addScore(1.0d);
                    break;
                case BETA:
                    candidate.addScore(0.35d);
                    break;
                default:
                    break;
            }
        }
    }

    public static final class QualityProfileRule implements ScoringRule {

        @Override
        public String name() {
            return "quality-profile";
        }

        @Override
        public void apply(ExecutionCandidate.Builder candidate, RuntimeDescriptor runtime, ExecutionRequest request, PlannerInputs inputs) {
            String quantization = candidate.build().quantization();
            if (quantization == null) {
                return;
            }
            Quantization parsed = Quantization.parse(quantization);
            double bits = parsed.bitsPerWeight();
            QualityProfile profile = request.qualityProfile();
            switch (profile) {
                case QUALITY_FIRST:
                    candidate.addScore(bits / 16.0d * 2.0d);
                    break;
                case MEMORY_FIRST:
                case LATENCY_FIRST:
                    candidate.addScore((16.0d - Math.min(16.0d, bits)) / 16.0d * 1.2d);
                    break;
                case BALANCED:
                case CUSTOM:
                default:
                    candidate.addScore(bits / 16.0d * 0.5d);
                    break;
            }
        }
    }

    public static final class LocalityRule implements ScoringRule {

        @Override
        public String name() {
            return "locality";
        }

        @Override
        public void apply(ExecutionCandidate.Builder candidate, RuntimeDescriptor runtime, ExecutionRequest request, PlannerInputs inputs) {
            if (candidate.build().device() != DeviceType.REMOTE) {
                candidate.addScore(0.5d);
            }
        }
    }
}
