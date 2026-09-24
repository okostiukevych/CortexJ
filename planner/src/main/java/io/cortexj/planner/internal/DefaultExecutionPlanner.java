package io.cortexj.planner.internal;

import io.cortexj.ErrorCode;
import io.cortexj.diagnostics.Recommendation;
import io.cortexj.diagnostics.SupportReport;
import io.cortexj.error.ModelResolutionException;
import io.cortexj.error.PlanningException;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.Checksum;
import io.cortexj.model.ModelArtifact;
import io.cortexj.model.ModelDescriptor;
import io.cortexj.model.ModelReference;
import io.cortexj.planner.ExecutionCandidate;
import io.cortexj.planner.ExecutionPlan;
import io.cortexj.planner.ExecutionRequest;
import io.cortexj.planner.FallbackPolicy;
import io.cortexj.planner.MemoryEstimate;
import io.cortexj.planner.PlanDownload;
import io.cortexj.planner.PlanReason;
import io.cortexj.planner.Planner;
import io.cortexj.planner.ReasonCode;
import io.cortexj.planner.internal.constraint.HardConstraint;
import io.cortexj.planner.internal.constraint.HardConstraints;
import io.cortexj.planner.internal.scoring.ScoringRules;
import io.cortexj.runtime.DeviceType;
import io.cortexj.runtime.RuntimeDescriptor;
import io.cortexj.spi.policy.ExecutionPolicy;
import io.cortexj.spi.policy.PolicyDecision;
import io.cortexj.spi.repository.ModelRepositoryProvider;
import io.cortexj.spi.repository.ResolvedArtifact;
import io.cortexj.spi.repository.ResolvedModel;
import io.cortexj.spi.repository.ResolutionContext;
import io.cortexj.spi.repository.TokenSupplier;
import io.cortexj.spi.runtime.RuntimeEvaluationRequest;
import io.cortexj.spi.runtime.RuntimeProbeContext;
import io.cortexj.spi.runtime.RuntimeProbeResult;
import io.cortexj.spi.runtime.RuntimeProvider;
import io.cortexj.spi.runtime.RuntimeSupport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DefaultExecutionPlanner implements Planner {

    public static final String PLANNER_VERSION = "1";

    private final PlannerInputs inputs;
    private final MemoryEstimator memoryEstimator;
    private final List<HardConstraint> constraints;
    private final List<ScoringRules.ScoringRule> scoringRules;

    public DefaultExecutionPlanner(PlannerInputs inputs) {
        this.inputs = inputs;
        this.memoryEstimator = new MemoryEstimator();
        this.constraints = HardConstraints.defaults();
        this.scoringRules = ScoringRules.defaults();
    }

    @Override
    public ExecutionPlan plan(ExecutionRequest request) {
        Outcome outcome = runPipeline(request);
        if (outcome.viable.isEmpty()) {
            if (request.requiredRuntime() != null) {
                PlanningException impossible = new PlanningException(ErrorCode.PLAN_USER_REQUIREMENT_IMPOSSIBLE,
                        "Cannot satisfy the required runtime '" + request.requiredRuntime()
                                + "' for model " + request.model().value(),
                        mostUsefulRemediation(outcome));
                impossible.detail("model", request.model().value());
                impossible.detail("requiredRuntime", request.requiredRuntime());
                for (SupportReport.RejectedCandidate rejected : outcome.rejected) {
                    impossible.detail("rejected:" + rejected.candidate().stableKey(), rejected.reason().message());
                }
                throw impossible;
            }
            PlanningException noCandidate = new PlanningException(ErrorCode.PLAN_NO_CANDIDATE,
                    "No viable execution path for model " + request.model().value(),
                    mostUsefulRemediation(outcome));
            noCandidate.detail("model", request.model().value());
            for (SupportReport.RejectedCandidate rejected : outcome.rejected) {
                noCandidate.detail("rejected:" + rejected.candidate().stableKey(), rejected.reason().message());
            }
            throw noCandidate;
        }

        ExecutionCandidate winner = outcome.viable.get(0);
        List<ExecutionCandidate> fallbacks = fallbackChain(request, outcome);

        List<PlanDownload> downloads = new ArrayList<PlanDownload>();
        downloads.addAll(downloadsFor(winner, outcome.resolved));
        for (ExecutionCandidate fallback : fallbacks) {
            downloads.addAll(downloadsFor(fallback, outcome.resolved));
        }

        List<PlanReason> reasons = new ArrayList<PlanReason>();
        for (PlanReason reason : winner.notesAsReasons()) {
            reasons.add(reason);
        }
        reasons.add(PlanReason.positive(ReasonCode.RUNTIME_MATURITY_HIGH,
                "runtime '" + winner.runtimeId() + "' reports support for this workload"));

        List<String> nativeRequirements = new ArrayList<String>();
        RuntimeDescriptor runtime = outcome.runtimeById.get(winner.runtimeId());
        if (runtime != null && runtime.isNativeRuntime()) {
            nativeRequirements.add(runtime.id() + " native library for " + inputs.environment.osArchKey());
        }

        String explanation = ExplanationBuilder.build(outcome.resolved, winner, fallbacks, reasons,
                outcome.warnings, outcome.runtimeHealthLines, inputs);

        return new ExecutionPlan(
                outcome.resolved.descriptor().canonicalId(),
                outcome.resolved.revision(),
                outcome.resolved,
                winner,
                fallbacks,
                reasons,
                outcome.warnings,
                winner.memoryEstimate(),
                downloads,
                nativeRequirements,
                explanation,
                inputs.platformVersion,
                PLANNER_VERSION,
                digest(outcome, request));
    }

    public SupportReport inspect(ExecutionRequest request) {
        try {
            Outcome outcome = runPipeline(request);
            List<PlanDownload> downloads = new ArrayList<PlanDownload>();
            for (ExecutionCandidate candidate : outcome.viable) {
                downloads.addAll(downloadsFor(candidate, outcome.resolved));
            }
            String explanation = ExplanationBuilder.buildSupportExplanation(
                    ExplanationBuilder.adapt(outcome.resolved.descriptor().canonicalId(),
                            outcome.resolved.revision(),
                            outcome.viable,
                            outcome.rejected),
                    inputs);
            return new SupportReport(
                    outcome.resolved.descriptor().canonicalId(),
                    outcome.resolved.revision(),
                    outcome.viable,
                    outcome.rejected,
                    downloads,
                    recommendations(outcome),
                    outcome.warnings,
                    explanation);
        } catch (ModelResolutionException e) {
            return new SupportReport(request.model().value(), null,
                    Collections.<ExecutionCandidate>emptyList(),
                    Collections.<SupportReport.RejectedCandidate>emptyList(),
                    Collections.<PlanDownload>emptyList(),
                    Collections.singletonList(new Recommendation("model-unresolved", e.getMessage())),
                    Collections.singletonList(PlanReason.warning(ReasonCode.REVISION_UNRESOLVED, e.getMessage())),
                    e.getMessage());
        }
    }

    private Outcome runPipeline(ExecutionRequest request) {
        ResolvedModel resolved = resolveModel(request);
        Outcome outcome = new Outcome();
        outcome.resolved = resolved;

        List<RuntimeProvider> providers = sortedProviders();
        Map<String, RuntimeProbeResult> probes = new LinkedHashMap<String, RuntimeProbeResult>();

        for (RuntimeProvider provider : providers) {
            RuntimeDescriptor descriptor = provider.descriptor();
            RuntimeProbeResult probe = provider.probe(new RuntimeProbeContext(
                    inputs.environment, inputs.hardware, inputs.offline, inputs.runtimeConfiguration()));
            probes.put(descriptor.id().value(), probe);
            outcome.runtimeById.put(descriptor.id().value(), descriptor);
            outcome.runtimeHealthLines.add(descriptor.id().value() + ": " + probe.health().name().toLowerCase()
                    + (probe.message().isEmpty() ? "" : " (" + probe.message() + ")"));
        }

        for (RuntimeProvider provider : providers) {
            RuntimeDescriptor descriptor = provider.descriptor();
            RuntimeProbeResult probe = probes.get(descriptor.id().value());
            if (descriptor.devices().contains(DeviceType.REMOTE) && !isRemoteSyntheticBlocked(request)) {
                ModelArtifact remoteArtifact = remoteArtifact(resolved);
                addEvaluatedCandidate(outcome, provider, descriptor, probe, remoteArtifact, request, resolved,
                        false, false);
            }
            for (ResolvedArtifact resolvedArtifact : sortedArtifacts(resolved)) {
                ModelArtifact artifact = resolvedArtifact.artifact();
                if (!artifact.format().isExecutableWeights()) {
                    continue;
                }
                String scheme = resolvedArtifact.location() == null ? "" : resolvedArtifact.location().getScheme();
                boolean localFile = scheme.equals("file");
                boolean cached = !localFile
                        && inputs.isCached(resolved.repository().value(), resolved.revision(), artifact);
                addEvaluatedCandidate(outcome, provider, descriptor, probe, artifact, request, resolved,
                        localFile, cached);
            }
        }

        applyConstraints(outcome, request, resolved, probes);
        applyPolicies(outcome, request);
        scoreAndSort(outcome, request);
        return outcome;
    }

    private boolean isRemoteSyntheticBlocked(ExecutionRequest request) {
        return request.isOffline() || !inputs.remoteAllowed;
    }

    private void addEvaluatedCandidate(Outcome outcome,
                                       RuntimeProvider provider,
                                       RuntimeDescriptor descriptor,
                                       RuntimeProbeResult probe,
                                       ModelArtifact artifact,
                                       ExecutionRequest request,
                                       ResolvedModel resolved,
                                       boolean localFile,
                                       boolean cached) {
        RuntimeSupport support;
        try {
            support = provider.evaluate(new RuntimeEvaluationRequest(
                    resolved.descriptor(), artifact, inputs.environment, inputs.hardware, request));
        } catch (RuntimeException e) {
            outcome.warnings.add(PlanReason.warning(ReasonCode.RUNTIME_UNAVAILABLE,
                    "runtime " + descriptor.id().value() + " failed evaluation: " + e.getMessage()));
            return;
        }
        if (support == null || !support.isSupported()) {
            for (PlanReason reason : support == null
                    ? Collections.singletonList(PlanReason.negative(ReasonCode.FORMAT_UNSUPPORTED,
                            "runtime " + descriptor.id().value() + " reported no support"))
                    : support.reasons()) {
                outcome.rejected.add(new SupportReport.RejectedCandidate(
                        ExecutionCandidate.builder(descriptor.id().value())
                                .artifact(artifact)
                                .device(DeviceType.CPU)
                                .build(),
                        reason));
            }
            return;
        }

        ArtifactInspectionHolder inspection = inspectFor(resolved, artifact);

        ExecutionCandidate.Builder builder = ExecutionCandidate.builder(descriptor.id().value())
                .artifact(artifact)
                .device(support.proposedDevice() == null ? DeviceType.CPU : support.proposedDevice())
                .requiresDownload(!localFile && !cached);
        if (localFile) {
            builder.note("localFile", "true");
        } else if (cached) {
            builder.note("cached", "true");
        }

        String quantization = artifact.metadata().get("quantization");
        if (quantization == null && inspection != null) {
            quantization = inspection.quantization;
        }
        if (quantization == null) {
            quantization = io.cortexj.model.descriptor.DescriptorEnricher.quantizationFromFileName(artifact.id());
        }
        builder.quantization(quantization);

        int contextLength = resolved.descriptor().contextLength();
        if (contextLength <= 0 && inspection != null && inspection.contextLength > 0) {
            contextLength = inspection.contextLength;
        }
        MemoryEstimate estimate = support.memoryEstimateOverride() != null
                ? support.memoryEstimateOverride()
                : memoryEstimator.estimate(artifact, contextLength);
        builder.memoryEstimate(estimate);

        outcome.builderByCandidateKey.put(candidateKey(descriptor.id().value(), artifact.id()), builder);
        outcome.candidates.add(new CandidateHolder(descriptor, probe, builder));
    }

    private void applyConstraints(Outcome outcome, ExecutionRequest request, ResolvedModel resolved, Map<String, RuntimeProbeResult> probes) {
        List<CandidateHolder> survivors = new ArrayList<CandidateHolder>();
        for (CandidateHolder holder : outcome.candidates) {
            HardConstraint.ConstraintContext context = new HardConstraint.ConstraintContext(
                    holder.descriptor, holder.probe, inputs.environment, inputs.hardware, request,
                    resolved.descriptor(), inputs.offline, inputs.remoteAllowed);
            PlanReason violation = null;
            for (HardConstraint constraint : constraints) {
                violation = constraint.check(holder.builder.build(), context);
                if (violation != null) {
                    break;
                }
            }
            if (violation == null) {
                survivors.add(holder);
            } else {
                outcome.rejected.add(new SupportReport.RejectedCandidate(holder.builder.build(), violation));
            }
        }
        outcome.candidates = survivors;
    }

    private void applyPolicies(Outcome outcome, ExecutionRequest request) {
        if (inputs.policies.isEmpty()) {
            return;
        }
        List<CandidateHolder> survivors = new ArrayList<CandidateHolder>();
        io.cortexj.spi.policy.PlanningContext planningContext = inputs.toPlanningContext(request);
        for (CandidateHolder holder : outcome.candidates) {
            boolean rejected = false;
            for (ExecutionPolicy policy : inputs.policies) {
                PolicyDecision decision = policy.evaluate(holder.builder.build(), planningContext);
                if (decision == null) {
                    continue;
                }
                if (decision.isRejection()) {
                    outcome.rejected.add(new SupportReport.RejectedCandidate(holder.builder.build(), decision.asReason()));
                    rejected = true;
                    break;
                }
                if (decision.scoreAdjustment() != 0.0d) {
                    holder.builder.addScore(decision.scoreAdjustment());
                    if (decision.reasonCode() != null) {
                        holder.builder.note("policy:" + policy.policyId(), decision.message());
                    }
                }
            }
            if (!rejected) {
                survivors.add(holder);
            }
        }
        outcome.candidates = survivors;
    }

    private void scoreAndSort(Outcome outcome, ExecutionRequest request) {
        for (CandidateHolder holder : outcome.candidates) {
            for (ScoringRules.ScoringRule rule : scoringRules) {
                rule.apply(holder.builder, holder.descriptor, request, inputs);
            }
        }
        List<CandidateHolder> scored = new ArrayList<CandidateHolder>(outcome.candidates);
        scored.sort(new Comparator<CandidateHolder>() {
            @Override
            public int compare(CandidateHolder a, CandidateHolder b) {
                int byScore = Double.compare(b.builder.build().score(), a.builder.build().score());
                if (byScore != 0) {
                    return byScore;
                }
                return a.builder.build().stableKey().compareTo(b.builder.build().stableKey());
            }
        });
        for (CandidateHolder holder : scored) {
            outcome.viable.add(holder.builder.build());
        }
    }

    private List<ExecutionCandidate> fallbackChain(ExecutionRequest request, Outcome outcome) {
        if (!request.isFallbackEnabled()) {
            return Collections.emptyList();
        }
        List<ExecutionCandidate> fallbacks = new ArrayList<ExecutionCandidate>();
        for (int i = 1; i < outcome.viable.size(); i++) {
            fallbacks.add(outcome.viable.get(i));
        }
        return fallbacks;
    }

    private ResolvedModel resolveModel(ExecutionRequest request) {
        ModelReference reference = request.model();
        ModelResolutionException last = null;
        for (ModelRepositoryProvider repository : sortedRepositories()) {
            if (!repository.supports(reference)) {
                continue;
            }
            try {
                return repository.resolve(reference, resolutionContext());
            } catch (RuntimeException e) {
                if (e instanceof ModelResolutionException) {
                    last = (ModelResolutionException) e;
                    continue;
                }
                throw e;
            }
        }
        if (last != null) {
            throw last;
        }
        throw new ModelResolutionException(ErrorCode.MODEL_NOT_FOUND,
                "No repository provider can resolve model reference: " + reference.value(),
                "Check the reference format or register a repository provider.")
                .detail("reference", reference.value());
    }

    private ResolutionContext resolutionContext() {
        final PlannerInputs captured = inputs;
        return new ResolutionContext() {
            @Override
            public boolean isOffline() {
                return captured.offline;
            }

            @Override
            public TokenSupplier tokenSupplier() {
                return TokenSupplier.none();
            }

            @Override
            public java.util.concurrent.ExecutorService executor() {
                return null;
            }

            @Override
            public io.cortexj.observability.PlatformEventListener eventListener() {
                return io.cortexj.observability.PlatformEventListener.NOOP;
            }
        };
    }

    private List<RuntimeProvider> sortedProviders() {
        List<RuntimeProvider> providers = new ArrayList<RuntimeProvider>(inputs.runtimeProviders);
        providers.sort(new Comparator<RuntimeProvider>() {
            @Override
            public int compare(RuntimeProvider a, RuntimeProvider b) {
                return a.id().value().compareTo(b.id().value());
            }
        });
        return providers;
    }

    private List<ModelRepositoryProvider> sortedRepositories() {
        List<ModelRepositoryProvider> repositories = new ArrayList<ModelRepositoryProvider>(inputs.repositoryProviders);
        repositories.sort(new Comparator<ModelRepositoryProvider>() {
            @Override
            public int compare(ModelRepositoryProvider a, ModelRepositoryProvider b) {
                return a.id().value().compareTo(b.id().value());
            }
        });
        return repositories;
    }

    private static List<ResolvedArtifact> sortedArtifacts(ResolvedModel resolved) {
        List<ResolvedArtifact> artifacts = new ArrayList<ResolvedArtifact>(resolved.artifacts());
        artifacts.sort(new Comparator<ResolvedArtifact>() {
            @Override
            public int compare(ResolvedArtifact a, ResolvedArtifact b) {
                return a.artifact().id().compareTo(b.artifact().id());
            }
        });
        return artifacts;
    }

    private List<PlanDownload> downloadsFor(ExecutionCandidate candidate, ResolvedModel resolved) {
        if (!candidate.requiresDownload() || candidate.artifact() == null) {
            return Collections.emptyList();
        }
        for (ResolvedArtifact artifact : resolved.artifacts()) {
            if (artifact.artifact().id().equals(candidate.artifact().id())) {
                return PlanDownload.singleton(new PlanDownload(
                        artifact.artifact().id(),
                        artifact.location() == null ? resolved.repository().value() : artifact.location().toString(),
                        artifact.artifact().sizeBytes()));
            }
        }
        return Collections.emptyList();
    }

    private ArtifactInspectionHolder inspectFor(ResolvedModel resolved, ModelArtifact artifact) {
        for (ResolvedArtifact resolvedArtifact : resolved.artifacts()) {
            if (resolvedArtifact.artifact().id().equals(artifact.id())) {
                io.cortexj.spi.artifact.ArtifactInspection inspection = inputs.inspectIfLocal(resolvedArtifact);
                if (inspection != null) {
                    return new ArtifactInspectionHolder(inspection.contextLength(), inspection.quantization());
                }
            }
        }
        return null;
    }

    private static ModelArtifact remoteArtifact(ResolvedModel resolved) {
        final String modelId = resolved.descriptor().canonicalId();
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        metadata.put("remoteModel", modelId);
        return new ModelArtifact.Abstract(modelId, ArtifactFormat.REMOTE, 0L, null, metadata) {
        };
    }

    private static String candidateKey(String runtimeId, String artifactId) {
        return runtimeId + "|" + artifactId;
    }

    private static String mostUsefulRemediation(Outcome outcome) {
        if (!outcome.rejected.isEmpty()) {
            return outcome.rejected.get(0).reason().message();
        }
        return "Install a runtime provider, configure a remote runtime, or check the model reference.";
    }

    private List<Recommendation> recommendations(Outcome outcome) {
        List<Recommendation> result = new ArrayList<Recommendation>();
        if (outcome.viable.isEmpty()) {
            boolean anyProviderMisconfigured = false;
            for (String line : outcome.runtimeHealthLines) {
                if (line.contains("misconfigured")) {
                    anyProviderMisconfigured = true;
                }
            }
            if (anyProviderMisconfigured) {
                result.add(new Recommendation("configure-runtime",
                        "At least one runtime is misconfigured; fix its configuration to enable it as a candidate."));
            }
            boolean anyMemoryRejected = false;
            for (SupportReport.RejectedCandidate rejected : outcome.rejected) {
                if (rejected.reason().code() == ReasonCode.MEMORY_LIMIT_EXCEEDED
                        || rejected.reason().code() == ReasonCode.MEMORY_INSUFFICIENT) {
                    anyMemoryRejected = true;
                }
            }
            if (anyMemoryRejected) {
                result.add(new Recommendation("memory",
                        "All candidates exceed memory limits; raise the limit or pick a smaller quantization."));
            }
            if (outcome.runtimeById.isEmpty()) {
                result.add(new Recommendation("install-runtime",
                        "No runtime providers are registered; add a cortexj starter or a runtime provider artifact."));
            }
        }
        for (ExecutionCandidate candidate : outcome.viable) {
            if (candidate.requiresDownload()) {
                result.add(new Recommendation("download",
                        "Candidate " + candidate.runtimeId() + " requires downloading "
                                + candidate.artifact().id() + " (~" + (candidate.artifact().sizeBytes() / (1024L * 1024L)) + " MiB)."));
            }
        }
        return result;
    }

    private String digest(Outcome outcome, ExecutionRequest request) {
        StringBuilder canonical = new StringBuilder();
        canonical.append(outcome.resolved.descriptor().canonicalId()).append('|');
        canonical.append(outcome.resolved.revision()).append('|');
        for (Map.Entry<String, RuntimeDescriptor> entry : outcome.runtimeById.entrySet()) {
            canonical.append(entry.getKey()).append(':').append(entry.getValue().version()).append(';');
        }
        canonical.append('|');
        for (ResolvedArtifact artifact : outcome.resolved.artifacts()) {
            canonical.append(artifact.artifact().id()).append(':')
                    .append(artifact.artifact().format()).append(':')
                    .append(artifact.artifact().sizeBytes()).append(':')
                    .append(artifact.artifact().checksum() == null ? "-" : artifact.artifact().checksum().value())
                    .append(';');
        }
        canonical.append('|');
        canonical.append(inputs.environment.osArchKey()).append(':')
                .append(inputs.environment.jvmFeatureVersion()).append(':')
                .append(inputs.environment.logicalCores()).append(':')
                .append(inputs.environment.totalPhysicalMemoryBytes());
        canonical.append('|');
        canonical.append(request.requiredRuntime()).append(':')
                .append(request.preferredRuntime()).append(':')
                .append(request.requiredDevice()).append(':')
                .append(request.memoryLimitBytes()).append(':')
                .append(request.qualityProfile()).append(':')
                .append(request.requiredFormat());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("sha256:");
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static final class ArtifactInspectionHolder {
        final int contextLength;
        final String quantization;

        ArtifactInspectionHolder(int contextLength, String quantization) {
            this.contextLength = contextLength;
            this.quantization = quantization;
        }
    }

    static final class CandidateHolder {
        final RuntimeDescriptor descriptor;
        final RuntimeProbeResult probe;
        final ExecutionCandidate.Builder builder;

        CandidateHolder(RuntimeDescriptor descriptor, RuntimeProbeResult probe, ExecutionCandidate.Builder builder) {
            this.descriptor = descriptor;
            this.probe = probe;
            this.builder = builder;
        }
    }

    static final class Outcome {
        ResolvedModel resolved;
        List<CandidateHolder> candidates = new ArrayList<CandidateHolder>();
        final List<ExecutionCandidate> viable = new ArrayList<ExecutionCandidate>();
        final List<SupportReport.RejectedCandidate> rejected = new ArrayList<SupportReport.RejectedCandidate>();
        final List<PlanReason> warnings = new ArrayList<PlanReason>();
        final Map<String, RuntimeDescriptor> runtimeById = new LinkedHashMap<String, RuntimeDescriptor>();
        final List<String> runtimeHealthLines = new ArrayList<String>();
        final Map<String, ExecutionCandidate.Builder> builderByCandidateKey = new LinkedHashMap<String, ExecutionCandidate.Builder>();
    }
}
