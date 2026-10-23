package io.cortexj.planner.internal.constraint;

import io.cortexj.planner.ExecutionCandidate;
import io.cortexj.planner.PlanReason;
import io.cortexj.planner.ReasonCode;
import io.cortexj.runtime.DeviceType;
import io.cortexj.runtime.RuntimeHealth;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public final class HardConstraints {

    private HardConstraints() {
    }

    public static List<HardConstraint> defaults() {
        List<HardConstraint> constraints = new ArrayList<HardConstraint>();
        constraints.add(new RuntimeAvailableConstraint());
        constraints.add(new JvmCompatibilityConstraint());
        constraints.add(new UserHardRequirementConstraint());
        constraints.add(new OfflineConstraint());
        constraints.add(new RemoteDisabledConstraint());
        constraints.add(new DeviceAvailabilityConstraint());
        constraints.add(new MemoryLimitConstraint());
        constraints.add(new PhysicalMemoryConstraint());
        constraints.add(new LicenseConstraint());
        constraints.add(new TaskCapabilityConstraint());
        return constraints;
    }

    /**
     * Multimodal planning: a runtime is only a candidate for a model
     * when it supports at least one task (or input modality) the model actually
     * offers - an embedding-only runtime must never win a text-generation model.
     */
    public static final class TaskCapabilityConstraint implements HardConstraint {

        @Override
        public PlanReason check(ExecutionCandidate candidate, HardConstraint.ConstraintContext context) {
            java.util.Set<io.cortexj.model.TaskCapability> modelTasks = context.model.tasks();
            if (modelTasks == null || modelTasks.isEmpty()) {
                return null;
            }
            java.util.Set<io.cortexj.model.TaskCapability> runtimeTasks = context.runtime.tasks();
            if (runtimeTasks == null || runtimeTasks.isEmpty()) {
                return null;
            }
            for (io.cortexj.model.TaskCapability task : modelTasks) {
                if (runtimeTasks.contains(task)) {
                    return null;
                }
            }
            return PlanReason.negative(ReasonCode.TASK_UNSUPPORTED,
                    "runtime " + context.runtime.id().value() + " supports " + runtimeTasks
                            + " but the model only offers " + modelTasks);
        }
    }

    public static final class RuntimeAvailableConstraint implements HardConstraint {

        @Override
        public PlanReason check(ExecutionCandidate candidate, HardConstraint.ConstraintContext context) {
            if (context.probe == null || !context.probe.isUsable()) {
                String message = context.probe == null
                        ? "runtime is not installed: " + candidate.runtimeId()
                        : "runtime " + candidate.runtimeId() + ": " + context.probe.message();
                return PlanReason.negative(ReasonCode.RUNTIME_UNAVAILABLE, message);
            }
            if (context.probe.health() == RuntimeHealth.MISCONFIGURED) {
                return PlanReason.negative(ReasonCode.RUNTIME_MISCONFIGURED,
                        "runtime " + candidate.runtimeId() + " is misconfigured: " + context.probe.message());
            }
            return null;
        }
    }

    public static final class JvmCompatibilityConstraint implements HardConstraint {

        @Override
        public PlanReason check(ExecutionCandidate candidate, HardConstraint.ConstraintContext context) {
            int currentFeature = context.environment.jvmFeatureVersion();
            int requiredFeature = context.runtime.minJavaFeatureVersion();
            if (currentFeature < requiredFeature) {
                return PlanReason.negative(ReasonCode.JVM_INCOMPATIBLE,
                        "runtime " + candidate.runtimeId() + " requires Java " + requiredFeature
                                + ", current JVM feature version is " + currentFeature);
            }
            return null;
        }
    }

    public static final class UserHardRequirementConstraint implements HardConstraint {

        @Override
        public PlanReason check(ExecutionCandidate candidate, HardConstraint.ConstraintContext context) {
            io.cortexj.planner.ExecutionRequest request = context.request;
            if (request.requiredRuntime() != null
                    && !request.requiredRuntime().equals(candidate.runtimeId())) {
                return PlanReason.negative(ReasonCode.USER_REQUIREMENT_CONFLICT,
                        "candidate " + candidate.runtimeId() + " does not match the required runtime '"
                                + request.requiredRuntime() + "'");
            }
            if (request.forbiddenRuntimes().contains(candidate.runtimeId())) {
                return PlanReason.negative(ReasonCode.FORBIDDEN_RUNTIME,
                        "runtime " + candidate.runtimeId() + " is explicitly forbidden by the user");
            }
            if (request.requiredFormat() != null && candidate.artifact() != null
                    && candidate.artifact().format() != request.requiredFormat()) {
                return PlanReason.negative(ReasonCode.USER_REQUIREMENT_CONFLICT,
                        "artifact format " + candidate.artifact().format()
                                + " does not match the required format " + request.requiredFormat());
            }
            if (request.requiredDevice() != null && request.requiredDevice() != io.cortexj.planner.DevicePreference.AUTO
                    && deviceTypeOf(request.requiredDevice()) != candidate.device()) {
                return PlanReason.negative(ReasonCode.USER_REQUIREMENT_CONFLICT,
                        "device " + candidate.device() + " does not match the required device " + request.requiredDevice());
            }
            if (!request.isDownloadAllowed() && candidate.requiresDownload()) {
                return PlanReason.negative(ReasonCode.USER_REQUIREMENT_CONFLICT,
                        "artifact requires a download but downloads are disabled by the user request");
            }
            return null;
        }

        private static DeviceType deviceTypeOf(io.cortexj.planner.DevicePreference preference) {
            switch (preference) {
                case CPU: return DeviceType.CPU;
                case GPU: return DeviceType.GPU;
                case NPU: return DeviceType.NPU;
                case REMOTE: return DeviceType.REMOTE;
                default: return DeviceType.AUTO;
            }
        }
    }

    public static final class OfflineConstraint implements HardConstraint {

        @Override
        public PlanReason check(ExecutionCandidate candidate, HardConstraint.ConstraintContext context) {
            if (!context.offline) {
                return null;
            }
            if (candidate.requiresDownload()) {
                return PlanReason.negative(ReasonCode.OFFLINE_DOWNLOAD_FORBIDDEN,
                        "artifact " + (candidate.artifact() == null ? "?" : candidate.artifact().id())
                                + " requires a download while offline mode is active");
            }
            if (candidate.device() == DeviceType.REMOTE) {
                return PlanReason.negative(ReasonCode.OFFLINE_DOWNLOAD_FORBIDDEN,
                        "remote execution is not possible while offline mode is active");
            }
            return null;
        }
    }

    public static final class RemoteDisabledConstraint implements HardConstraint {

        @Override
        public PlanReason check(ExecutionCandidate candidate, HardConstraint.ConstraintContext context) {
            if (candidate.device() == DeviceType.REMOTE && !context.remoteAllowed) {
                return PlanReason.negative(ReasonCode.REMOTE_DISABLED,
                        "remote execution is disabled in the platform configuration");
            }
            return null;
        }
    }

    public static final class DeviceAvailabilityConstraint implements HardConstraint {

        @Override
        public PlanReason check(ExecutionCandidate candidate, HardConstraint.ConstraintContext context) {
            if (candidate.device() == DeviceType.GPU && !context.hardware.hasAccelerator()) {
                return PlanReason.negative(ReasonCode.DEVICE_UNAVAILABLE,
                        "candidate requires a GPU accelerator but none was detected");
            }
            if (candidate.device() == DeviceType.NPU && context.hardware.devicesOfType(DeviceType.NPU).isEmpty()) {
                return PlanReason.negative(ReasonCode.DEVICE_UNAVAILABLE,
                        "candidate requires an NPU device but none was detected");
            }
            return null;
        }
    }

    public static final class MemoryLimitConstraint implements HardConstraint {

        @Override
        public PlanReason check(ExecutionCandidate candidate, HardConstraint.ConstraintContext context) {
            Long hardLimit = context.request.memoryLimitBytes();
            if (hardLimit == null || hardLimit <= 0) {
                return null;
            }
            if (!candidate.memoryEstimate().isKnown()) {
                // audit 2026-10-07 (#147): an unknown estimate used to sail
                // through every memory check - block it only under explicit
                // opt-in (default warn-and-allow preserves HTTP/no-size flows)
                if (blockUnknownMemory()) {
                    return PlanReason.negative(ReasonCode.MEMORY_LIMIT_EXCEEDED,
                            "memory estimate is unknown and unknown-memory candidates are blocked "
                                    + "(cortexj.planner.blockUnknownMemory=true)");
                }
                return null;
            }
            if (candidate.memoryEstimate().totalBytes() > hardLimit) {
                return PlanReason.negative(ReasonCode.MEMORY_LIMIT_EXCEEDED,
                        "estimated memory " + candidate.memoryEstimate()
                                + " exceeds the configured limit of " + mebiBytes(hardLimit) + " MiB");
            }
            return null;
        }
    }

    public static final class PhysicalMemoryConstraint implements HardConstraint {

        @Override
        public PlanReason check(ExecutionCandidate candidate, HardConstraint.ConstraintContext context) {
            if (!candidate.memoryEstimate().isKnown()) {
                // audit 2026-10-07 (#147): see MemoryLimitConstraint - same
                // opt-in fail-closed for the physical-memory check
                if (blockUnknownMemory()) {
                    return PlanReason.negative(ReasonCode.MEMORY_INSUFFICIENT,
                            "memory estimate is unknown and unknown-memory candidates are blocked "
                                    + "(cortexj.planner.blockUnknownMemory=true)");
                }
                return null;
            }
            long available = context.environment.availableMemoryEstimateBytes();
            if (available <= 0) {
                available = context.environment.totalPhysicalMemoryBytes();
            }
            if (available <= 0) {
                return null;
            }
            long heapSlack = context.environment.maxHeapBytes() > 0 ? context.environment.maxHeapBytes() / 4 : 0;
            long usable = Math.max(available, heapSlack);
            if (candidate.memoryEstimate().totalBytes() > usable) {
                return PlanReason.negative(ReasonCode.MEMORY_INSUFFICIENT,
                        "estimated memory " + candidate.memoryEstimate()
                                + " exceeds the ~" + mebiBytes(usable) + " MiB available on this machine");
            }
            return null;
        }
    }

    /**
     * audit 2026-10-07 (#147): opt-in fail-closed for unknown memory
     * estimates. Default stays warn-and-allow so size-less sources (plain
     * HTTP without Content-Length) keep working; strict deployments set
     * {@code cortexj.planner.blockUnknownMemory=true} (or
     * {@code CORTEXJ_PLANNER_BLOCK_UNKNOWN_MEMORY=1}).
     */
    static boolean blockUnknownMemory() {
        String property = System.getProperty("cortexj.planner.blockUnknownMemory");
        if (property != null) {
            return Boolean.parseBoolean(property.trim());
        }
        String env = System.getenv("CORTEXJ_PLANNER_BLOCK_UNKNOWN_MEMORY");
        return env != null && (env.trim().equals("1") || Boolean.parseBoolean(env.trim()));
    }

    public static final class LicenseConstraint implements HardConstraint {
        @Override
        public PlanReason check(ExecutionCandidate candidate, HardConstraint.ConstraintContext context) {
            if (context.request.allowedLicenses().isEmpty()) {
                return null;
            }
            String license = context.model.license();
            if (license == null || license.isEmpty()) {
                return PlanReason.negative(ReasonCode.LICENSE_BLOCKED,
                        "model license is unknown and the allowlist policy requires an allowed license");
            }
            for (String allowed : context.request.allowedLicenses()) {
                if (allowed.equalsIgnoreCase(license)) {
                    return null;
                }
            }
            return PlanReason.negative(ReasonCode.LICENSE_BLOCKED,
                    "model license '" + license + "' is not in the allowed license set "
                            + context.request.allowedLicenses());
        }
    }

    static String mebiBytes(long bytes) {
        return new BigDecimal(bytes).divide(new BigDecimal(1024L * 1024L)).toBigInteger().toString();
    }
}
