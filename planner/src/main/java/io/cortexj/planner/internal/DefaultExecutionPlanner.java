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
import io.cortexj.planner.ConversionStep;
import io.cortexj.planner.FallbackPolicy;
import io.cortexj.planner.ModelLockfile;
import io.cortexj.planner.ReproducibilityMode;
import io.cortexj.planner.MemoryEstimate;
import io.cortexj.planner.PlanDownload;
import io.cortexj.planner.PlanReason;
import io.cortexj.planner.Planner;
import io.cortexj.planner.ReasonCode;
import io.cortexj.planner.internal.constraint.HardConstraint;
import io.cortexj.planner.internal.constraint.HardConstraints;
import io.cortexj.planner.internal.scoring.BenchmarkScoringRule;
import io.cortexj.planner.internal.scoring.ScoringRules;
import io.cortexj.runtime.DeviceType;
import io.cortexj.runtime.RuntimeDescriptor;
import io.cortexj.spi.conversion.ConversionRequest;
import io.cortexj.spi.conversion.ConversionSupport;
import io.cortexj.spi.conversion.ModelConverter;
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
    private static final long DEFAULT_POSITIVE_PROBE_TTL_MILLIS = 60_000L;
    private static final long DEFAULT_NEGATIVE_PROBE_TTL_MILLIS = 10_000L;
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<CachedProbe>> probeFutures =
            new java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<CachedProbe>>();
    private volatile long positiveProbeTtlMillis = DEFAULT_POSITIVE_PROBE_TTL_MILLIS;
    private volatile long negativeProbeTtlMillis = DEFAULT_NEGATIVE_PROBE_TTL_MILLIS;

    public DefaultExecutionPlanner(PlannerInputs inputs) {
        this.inputs = inputs;
        this.memoryEstimator = new MemoryEstimator();
        this.constraints = HardConstraints.defaults();
        this.scoringRules = ScoringRules.defaults();
        if (!inputs.benchmarkHints.isEmpty()) {
            this.scoringRules.add(new BenchmarkScoringRule(inputs.benchmarkHints));
        }
    }

    @Override
    public ExecutionPlan plan(ExecutionRequest request) {
        long planningStartedMillis = System.currentTimeMillis();
        Outcome outcome = runPipeline(request);
        enforcePinning(request, outcome);
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
                attachRuntimeHealth(impossible, outcome);
                throw impossible;
            }
            PlanningException noCandidate = new PlanningException(ErrorCode.PLAN_NO_CANDIDATE,
                    "No viable execution path for model " + request.model().value(),
                    mostUsefulRemediation(outcome));
            noCandidate.detail("model", request.model().value());
            for (SupportReport.RejectedCandidate rejected : outcome.rejected) {
                noCandidate.detail("rejected:" + rejected.candidate().stableKey(), rejected.reason().message());
            }
            attachRuntimeHealth(noCandidate, outcome);
            throw noCandidate;
        }

        if (inputs.eventListener != null) {
            java.util.Map<String, String> attributes = new java.util.LinkedHashMap<String, String>();
            attributes.put("model", request.model().value());
            attributes.put("durationMillis",
                    String.valueOf(System.currentTimeMillis() - planningStartedMillis));
            inputs.eventListener.onEvent(new io.cortexj.observability.PlatformEvent(
                    io.cortexj.observability.PlatformEvent.Type.PLAN_CREATED,
                    io.cortexj.observability.PlatformEvent.Level.INFO,
                    "plan created for " + request.model().value(),
                    attributes, System.currentTimeMillis()));
        }

        ExecutionCandidate winner = outcome.viable.get(0);
        List<ExecutionCandidate> fallbacks = fallbackChain(request, outcome);

        // audit 2026-10-07 (#146): one artifact index per plan instead of a
        // linear scan per candidate (O(FxA) on wide repositories)
        java.util.Map<String, ResolvedArtifact> artifactIndex = artifactIndex(outcome.resolved);
        List<PlanDownload> downloads = new ArrayList<PlanDownload>();
        downloads.addAll(downloadsFor(winner, outcome.resolved, artifactIndex));
        for (ExecutionCandidate fallback : fallbacks) {
            downloads.addAll(downloadsFor(fallback, outcome.resolved, artifactIndex));
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

    private void enforcePinning(ExecutionRequest request, Outcome outcome) {
        ModelLockfile lockfile = request.lockfile();
        if (lockfile == null) {
            return;
        }
        if (lockfile.revision() != null && !lockfile.revision().equals(outcome.resolved.revision())) {
            PlanningException pinned = new PlanningException(ErrorCode.PLAN_USER_REQUIREMENT_IMPOSSIBLE,
                    "Pinned model revision '" + lockfile.revision() + "' does not match resolved revision '"
                            + outcome.resolved.revision() + "' for " + lockfile.model(),
                    "The repository content changed; re-verify the model or update the lockfile.");
            pinned.detail("lockfileRevision", lockfile.revision());
            pinned.detail("resolvedRevision", outcome.resolved.revision());
            throw pinned;
        }
        if (request.reproducibilityMode() != ReproducibilityMode.PINNED_PLAN) {
            return;
        }
        List<ExecutionCandidate> matching = new ArrayList<ExecutionCandidate>();
        List<SupportReport.RejectedCandidate> pinRejections = new ArrayList<SupportReport.RejectedCandidate>();
        for (ExecutionCandidate candidate : outcome.viable) {
            boolean runtimeMatches = !lockfile.pinsRuntime() || lockfile.runtimeId().equals(candidate.runtimeId());
            boolean artifactMatches = !lockfile.pinsArtifact()
                    || (candidate.artifact() != null && lockfile.artifactFile().equals(candidate.artifact().id()));
            if (runtimeMatches && artifactMatches) {
                if (lockfile.pinsArtifact() && lockfile.artifactSha256() != null) {
                    verifyArtifactChecksum(lockfile, candidate);
                }
                matching.add(candidate);
            } else {
                pinRejections.add(new SupportReport.RejectedCandidate(candidate,
                        PlanReason.negative(ReasonCode.USER_REQUIREMENT_CONFLICT,
                                "candidate " + candidate.stableKey() + " does not match the pinned plan ("
                                        + lockfile.runtimeId() + " / " + lockfile.artifactFile() + ")")));
            }
        }
        if (matching.isEmpty()) {
            PlanningException violated = new PlanningException(ErrorCode.PLAN_USER_REQUIREMENT_IMPOSSIBLE,
                    "PINNED_PLAN mode: no viable candidate matches the lockfile (runtime '"
                            + lockfile.runtimeId() + "', artifact '" + lockfile.artifactFile() + "').",
                    "The environment drifted from the locked plan; restore the pinned runtime/artifact "
                            + "or regenerate the lockfile explicitly.");
            violated.detail("lockfileRuntime", String.valueOf(lockfile.runtimeId()));
            violated.detail("lockfileArtifact", String.valueOf(lockfile.artifactFile()));
            for (SupportReport.RejectedCandidate rejected : pinRejections) {
                violated.detail("rejected:" + rejected.candidate().stableKey(), rejected.reason().message());
            }
            throw violated;
        }
        outcome.viable.retainAll(matching);
        outcome.rejected.addAll(pinRejections);
    }

    private void verifyArtifactChecksum(ModelLockfile lockfile, ExecutionCandidate candidate) {
        Checksum checksum = candidate.artifact().checksum();
        if (checksum == null) {
            PlanningException unverifiable = new PlanningException(ErrorCode.PLAN_USER_REQUIREMENT_IMPOSSIBLE,
                    "PINNED_PLAN mode: artifact '" + lockfile.artifactFile()
                            + "' declares no checksum; the lockfile sha256 pin cannot be verified.",
                    "The artifact is unpinned/unverifiable; use a repository that publishes a sha256 checksum "
                            + "for '" + lockfile.artifactFile() + "' or regenerate the lockfile explicitly.");
            unverifiable.detail("lockfileArtifact", lockfile.artifactFile());
            unverifiable.detail("lockfileSha256", lockfile.artifactSha256());
            throw unverifiable;
        }
        if (!checksum.value().equalsIgnoreCase(lockfile.artifactSha256())) {
            PlanningException mismatch = new PlanningException(ErrorCode.PLAN_USER_REQUIREMENT_IMPOSSIBLE,
                    "PINNED_PLAN mode: artifact '" + lockfile.artifactFile() + "' sha256 mismatch: lockfile sha '"
                            + lockfile.artifactSha256() + "' does not match resolved sha '"
                            + checksum.value() + "'.",
                    "The repository content changed under the same artifact name; re-verify the model "
                            + "or update the lockfile explicitly.");
            mismatch.detail("lockfileArtifact", lockfile.artifactFile());
            mismatch.detail("lockfileSha256", lockfile.artifactSha256());
            mismatch.detail("resolvedSha256", checksum.value());
            throw mismatch;
        }
    }

    public SupportReport inspect(ExecutionRequest request) {
        try {
            Outcome outcome = runPipeline(request);
            java.util.Map<String, ResolvedArtifact> artifactIndex = artifactIndex(outcome.resolved);
            List<PlanDownload> downloads = new ArrayList<PlanDownload>();
            for (ExecutionCandidate candidate : outcome.viable) {
                downloads.addAll(downloadsFor(candidate, outcome.resolved, artifactIndex));
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
        } catch (RuntimeException e) {
            return new SupportReport(request.model().value(), null,
                    Collections.<ExecutionCandidate>emptyList(),
                    Collections.<SupportReport.RejectedCandidate>emptyList(),
                    Collections.<PlanDownload>emptyList(),
                    Collections.singletonList(new Recommendation("inspection-failed",
                            "Inspection failed with an unexpected error: " + e.getMessage())),
                    Collections.singletonList(PlanReason.warning(ReasonCode.RUNTIME_UNAVAILABLE,
                            "unexpected failure during inspection: " + e.getMessage())),
                    "Inspection failed: " + e.getMessage());
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
            RuntimeProbeResult probe = probeCached(provider);
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
                        true, true);
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

        if (outcome.candidates.isEmpty()) {
            generateConversionCandidates(outcome, request, resolved, providers, probes);
        }

        applyConstraints(outcome, request, resolved, probes);
        applyPolicies(outcome, request);
        scoreAndSort(outcome, request);
        warnOnTrustBlock(outcome);
        warnOnDegradedWinner(outcome);
        return outcome;
    }

    /**
     * audit 2026-10-08 (#153): trust was enforced only at load, so
     * {@code inspect()} never showed it - surface the coming refusal as a
     * plan warning using the previously dead TRUST_POLICY_BLOCKED code.
     * Mirrors DefaultModels.enforceTrustPolicy without duplicating its
     * throw semantics (planning stays advisory, load stays enforcing).
     */
    private void warnOnTrustBlock(Outcome outcome) {
        if (inputs.trustPolicy != io.cortexj.model.TrustPolicy.TRUSTED_ONLY
                || outcome.resolved == null || outcome.resolved.descriptor() == null) {
            return;
        }
        String note = outcome.resolved.descriptor().trustNote();
        if (note != null && !note.trim().isEmpty()) {
            outcome.warnings.add(PlanReason.warning(io.cortexj.planner.ReasonCode.TRUST_POLICY_BLOCKED,
                    "model '" + outcome.resolved.descriptor().canonicalId()
                            + "' will be refused at load under TRUSTED_ONLY: " + note));
            return;
        }
        boolean checksummed = false;
        if (!outcome.viable.isEmpty() && outcome.viable.get(0).artifact() != null
                && outcome.viable.get(0).artifact().format() != ArtifactFormat.REMOTE) {
            checksummed = hasStrongChecksum(outcome.viable.get(0).artifact().checksum());
        } else if (outcome.resolved.artifacts() != null) {
            for (ResolvedArtifact artifact : outcome.resolved.artifacts()) {
                if (artifact != null && artifact.artifact() != null
                        && artifact.artifact().format().isExecutableWeights()
                        && hasStrongChecksum(artifact.artifact().checksum())) {
                    checksummed = true;
                    break;
                }
            }
        }
        if (!checksummed) {
            outcome.warnings.add(PlanReason.warning(io.cortexj.planner.ReasonCode.TRUST_POLICY_BLOCKED,
                    "model '" + outcome.resolved.descriptor().canonicalId()
                            + "' carries no trust note and no artifact checksum and will be "
                            + "refused at load under TRUSTED_ONLY."));
        }
    }

    private static boolean hasStrongChecksum(Checksum checksum) {
        if (checksum == null || checksum.value() == null || checksum.value().trim().isEmpty()
                || checksum.algorithm() == null) {
            return false;
        }
        String normalized = checksum.algorithm().trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("sha256") || normalized.equals("sha-256")
                || normalized.equals("sha384") || normalized.equals("sha-384")
                || normalized.equals("sha512") || normalized.equals("sha-512");
    }

    /**
     * Audit 2026-10-10: without this the only trace of running on a
     * degraded runtime is a notes entry - warn explicitly so the user is
     * prompted to investigate the endpoint/native setup.
     */
    private void warnOnDegradedWinner(Outcome outcome) {
        if (outcome.viable.isEmpty()) {
            return;
        }
        ExecutionCandidate winner = outcome.viable.get(0);
        if (winner.notes() == null || !"true".equals(winner.notes().get("degradedRuntime"))) {
            return;
        }
        String health = null;
        for (String line : outcome.runtimeHealthLines) {
            if (line != null && line.startsWith(winner.runtimeId() + ":")) {
                health = line;
                break;
            }
        }
        outcome.warnings.add(PlanReason.warning(ReasonCode.PLANNER_NOTE,
                "selected runtime '" + winner.runtimeId() + "' reports DEGRADED health"
                        + (health == null ? "" : " (" + health + ")")
                        + "; investigate the endpoint or native setup, or pin a healthy runtime "
                        + "with --runtime/--prefer-runtime."));
    }

    private void generateConversionCandidates(Outcome outcome,
                                              ExecutionRequest request,
                                              ResolvedModel resolved,
                                              List<RuntimeProvider> providers,
                                              Map<String, RuntimeProbeResult> probes) {
        if (inputs.converters.isEmpty()) {
            return;
        }
        for (RuntimeProvider provider : providers) {
            RuntimeDescriptor descriptor = provider.descriptor();
            RuntimeProbeResult probe = probes.get(descriptor.id().value());
            for (ResolvedArtifact resolvedArtifact : sortedArtifacts(resolved)) {
                ModelArtifact source = resolvedArtifact.artifact();
                if (!source.format().isExecutableWeights()) {
                    continue;
                }
                String scheme = resolvedArtifact.location() == null ? "" : resolvedArtifact.location().getScheme();
                boolean localFile = scheme.equals("file");
                boolean cached = !localFile
                        && inputs.isCached(resolved.repository().value(), resolved.revision(), source);
                for (ArtifactFormat targetFormat : descriptor.formats()) {
                    if (targetFormat == source.format()) {
                        continue;
                    }
                    ModelConverter converter = findConverter(source, targetFormat);
                    if (converter == null) {
                        continue;
                    }
                    addConversionCandidate(outcome, provider, descriptor, probe, source,
                            converter, targetFormat, request, resolved, localFile, cached);
                }
            }
        }
    }

    private ModelConverter findConverter(ModelArtifact source, ArtifactFormat targetFormat) {
        ModelConverter best = null;
        for (ModelConverter converter : inputs.converters) {
            ConversionSupport support;
            try {
                support = converter.evaluate(new ConversionRequest(source, null, targetFormat, null, null,
                        java.util.Collections.<String, Object>emptyMap()));
            } catch (RuntimeException evaluationFailure) {
                continue;
            }
            if (support != null && support.isSupported()) {
                if (best == null || converter.id().value().compareTo(best.id().value()) < 0) {
                    best = converter;
                }
            }
        }
        return best;
    }

    private void addConversionCandidate(Outcome outcome,
                                        RuntimeProvider provider,
                                        RuntimeDescriptor descriptor,
                                        RuntimeProbeResult probe,
                                        ModelArtifact source,
                                        ModelConverter converter,
                                        ArtifactFormat targetFormat,
                                        ExecutionRequest request,
                                        ResolvedModel resolved,
                                        boolean localFile,
                                        boolean cached) {
        final Map<String, String> metadata = new LinkedHashMap<String, String>(source.metadata());
        metadata.put("convertedFrom", source.id());
        ModelArtifact converted = new ModelArtifact.Abstract(
                source.id() + "." + targetFormat.name().toLowerCase(),
                targetFormat, source.sizeBytes(), null, metadata) {
        };

        RuntimeSupport support;
        try {
            support = provider.evaluate(new RuntimeEvaluationRequest(
                    resolved.descriptor(), converted, inputs.environment, inputs.hardware, request));
        } catch (RuntimeException evaluationFailure) {
            return;
        }
        if (support == null || !support.isSupported()) {
            return;
        }

        ConversionStep step = new ConversionStep(converter.id().value(), source.id(),
                source.format(), targetFormat, null);

        ExecutionCandidate.Builder builder = ExecutionCandidate.builder(descriptor.id().value())
                .artifact(converted)
                .license(resolved.descriptor().license())
                .device(support.proposedDevice() == null ? DeviceType.CPU : support.proposedDevice())
                .requiresDownload(!localFile && !cached)
                .conversion(step)
                .score(-30.0d);
        builder.note("conversion", converter.id().value());
        if (localFile) {
            builder.note("localFile", "true");
        } else if (cached) {
            builder.note("cached", "true");
        }
        String quantization = source.metadata().get("quantization");
        builder.quantization(quantization == null
                ? io.cortexj.model.descriptor.DescriptorEnricher.quantizationFromFileName(source.id())
                : quantization);
        int contextLength = resolved.descriptor().contextLength();
        builder.memoryEstimate(memoryEstimator.estimate(converted, contextLength));
        builder.note("supportLevel", support.level().name());
        builder.note("probeHealth", probeHealthOf(probe));

        outcome.candidates.add(new CandidateHolder(descriptor, probe, builder));
    }

    private RuntimeProbeResult probeCached(RuntimeProvider provider) {
        String key = provider.id().value();
        // audit 2026-10-08 (#153): open circuit short-circuits before any
        // claim - explicit invalidations cannot force physical probes through
        // it until the cooldown passes (that is the point of the breaker)
        if (circuitOpen(key)) {
            return io.cortexj.spi.runtime.RuntimeProbeResult.unavailable(
                    "circuit open for '" + key + "': repeated probe failures, cooling down");
        }
        for (int attempt = 0; attempt < 4; attempt++) {
            long now = System.currentTimeMillis();
            java.util.concurrent.CompletableFuture<CachedProbe> seen = probeFutures.get(key);
            if (seen != null) {
                CachedProbe cached = resultOf(seen);
                if (cached != null && now < cached.expiresAtMillis) {
                    return cached.result;
                }
            }
            java.util.concurrent.CompletableFuture<CachedProbe> claim =
                    new java.util.concurrent.CompletableFuture<CachedProbe>();
            java.util.concurrent.CompletableFuture<CachedProbe> racing = probeFutures.putIfAbsent(key, claim);
            if (racing == null) {
                boolean completed = false;
                try {
                    CachedProbe probed = probeOnce(provider);
                    claim.complete(probed);
                    completed = true;
                    return probed.result;
                } finally {
                    if (!completed) {
                        claim.completeExceptionally(new IllegalStateException(
                                "probe aborted for " + key));
                        probeFutures.remove(key, claim);
                    }
                }
            }
            CachedProbe cached = resultOf(racing);
            if (cached == null) {
                return awaitProbeResult(provider, key, racing);
            }
            if (now < cached.expiresAtMillis) {
                return cached.result;
            }
            probeFutures.remove(key, racing);
        }
        return probeOnce(provider).result;
    }

    private static CachedProbe resultOf(java.util.concurrent.CompletableFuture<CachedProbe> future) {
        // getNow(null) violates the JDK nullness contract and throws an
        // unhandled CompletionException on exceptional completion; a done
        // future is joined safely, an exceptional completion counts as absent
        if (!future.isDone()) {
            return null;
        }
        try {
            return future.join();
        } catch (java.util.concurrent.CompletionException failure) {
            return null;
        }
    }

    private CachedProbe probeOnce(RuntimeProvider provider) {
        RuntimeProbeResult result;
        try {
            result = provider.probe(new RuntimeProbeContext(
                    inputs.environment, inputs.hardware, inputs.offline, inputs.runtimeConfiguration()));
        } catch (RuntimeException probeFailure) {
            result = io.cortexj.spi.runtime.RuntimeProbeResult.unavailable(
                    "probe threw: " + probeFailure.getMessage());
        }
        long ttl = result.isUsable() ? positiveProbeTtlMillis : negativeProbeTtlMillis;
        String key = probeKey(provider);
        if (result.isUsable()) {
            consecutiveProbeFailures.remove(key);
            lastProbeFailureMillis.remove(key);
        } else {
            // audit 2026-10-08 (#153): minimal circuit breaker - count and
            // timestamp consecutive failures; probeCached gates on them
            incrementProbeFailures(key);
            lastProbeFailureMillis.put(key, Long.valueOf(System.currentTimeMillis()));
        }
        return new CachedProbe(result, System.currentTimeMillis() + ttl);
    }

    private static final int CIRCUIT_BREAKER_THRESHOLD = 5;

    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>
            consecutiveProbeFailures = new java.util.concurrent.ConcurrentHashMap<String,
            java.util.concurrent.atomic.AtomicInteger>();
    private final java.util.concurrent.ConcurrentHashMap<String, Long> lastProbeFailureMillis =
            new java.util.concurrent.ConcurrentHashMap<String, Long>();

    private static long circuitCooldownMillis() {
        String property = System.getProperty("cortexj.planner.circuitCooldownMillis");
        if (property != null && !property.trim().isEmpty()) {
            try {
                long parsed = Long.parseLong(property.trim());
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return 60_000L;
    }

    private boolean circuitOpen(String key) {
        java.util.concurrent.atomic.AtomicInteger counter = consecutiveProbeFailures.get(key);
        if (counter == null || counter.get() < CIRCUIT_BREAKER_THRESHOLD) {
            return false;
        }
        Long last = lastProbeFailureMillis.get(key);
        long now = System.currentTimeMillis();
        if (last == null || now - last.longValue() >= circuitCooldownMillis()) {
            // half-open: let one trial through with counter at threshold - 1 so a
            // single failed trial immediately re-opens the circuit instead of
            // allowing another full threshold of failures
            if (last != null && !lastProbeFailureMillis.replace(key, last, Long.valueOf(now))) {
                return true;
            }
            counter.set(CIRCUIT_BREAKER_THRESHOLD - 1);
            return false;
        }
        return true;
    }

    private static String probeKey(RuntimeProvider provider) {
        try {
            return provider.id() == null ? provider.getClass().getName() : provider.id().value();
        } catch (RuntimeException unreadable) {
            return provider.getClass().getName();
        }
    }

    private int incrementProbeFailures(String key) {
        java.util.concurrent.atomic.AtomicInteger counter = consecutiveProbeFailures.get(key);
        if (counter == null) {
            java.util.concurrent.atomic.AtomicInteger fresh =
                    new java.util.concurrent.atomic.AtomicInteger(0);
            java.util.concurrent.atomic.AtomicInteger raced =
                    consecutiveProbeFailures.putIfAbsent(key, fresh);
            counter = raced == null ? fresh : raced;
        }
        return counter.incrementAndGet();
    }

    private static final long PROBE_WAIT_MILLIS = probeWaitMillis();

    private static long probeWaitMillis() {
        // audit 2026-10-08: the 60 s default stands, but strict deployments
        // tune it without rebuilding
        String property = System.getProperty("cortexj.planner.probeWaitMillis");
        if (property != null && !property.trim().isEmpty()) {
            try {
                long parsed = Long.parseLong(property.trim());
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return 60_000L;
    }

    private RuntimeProbeResult awaitProbeResult(RuntimeProvider provider, String key,
                                                java.util.concurrent.CompletableFuture<CachedProbe> inFlight) {
        try {
            // audit 2026-10-07 (#147): a hung native probe used to hang the
            // planner forever on untimed get() - bound the wait, then fall
            // back to a direct probe
            return inFlight.get(PROBE_WAIT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS).result;
        } catch (java.util.concurrent.TimeoutException timedOut) {
            probeFutures.remove(key, inFlight);
            return probeOnce(provider).result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return probeOnce(provider).result;
        } catch (java.util.concurrent.ExecutionException e) {
            probeFutures.remove(key, inFlight);
            return probeOnce(provider).result;
        }
    }

    public io.cortexj.spi.runtime.RuntimeProbeResult probeShared(RuntimeProvider provider) {
        return probeCached(provider);
    }

    public void invalidateProbeCache() {
        // audit #116: clear only COMPLETED entries - sweeping in-flight probe
        // claims orphaned the running probe and made the next call re-run the
        // physical probe of the same runtime concurrently
        for (java.util.Map.Entry<String, java.util.concurrent.CompletableFuture<CachedProbe>> entry
                : probeFutures.entrySet()) {
            if (entry.getValue().isDone()) {
                probeFutures.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    void setProbeTtlsForTesting(long positiveMillis, long negativeMillis) {
        this.positiveProbeTtlMillis = positiveMillis;
        this.negativeProbeTtlMillis = negativeMillis;
    }

    private static final class CachedProbe {
        final RuntimeProbeResult result;
        final long expiresAtMillis;

        CachedProbe(RuntimeProbeResult result, long expiresAtMillis) {
            this.result = result;
            this.expiresAtMillis = expiresAtMillis;
        }
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
                .license(resolved.descriptor().license())
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
        // audit 2026-10-08: the default is warn-and-allow for unknown
        // estimates (fail-closed is opt-in via blockUnknownMemory) - make the
        // warn half real instead of a silent pass-through
        if (!estimate.isKnown()) {
            outcome.warnings.add(PlanReason.warning(ReasonCode.PLANNER_NOTE,
                    "memory estimate for artifact '" + artifact.id() + "' is unknown; "
                            + "memory checks pass vacuously unless "
                            + "cortexj.planner.blockUnknownMemory=true"));
        }
        builder.note("supportLevel", support.level().name());
        builder.note("probeHealth", probeHealthOf(probe));

        outcome.builderByCandidateKey.put(candidateKey(descriptor.id().value(), artifact.id()), builder);
        outcome.candidates.add(new CandidateHolder(descriptor, probe, builder));
    }

    private void applyConstraints(Outcome outcome, ExecutionRequest request, ResolvedModel resolved, Map<String, RuntimeProbeResult> probes) {
        List<CandidateHolder> survivors = new ArrayList<CandidateHolder>();
        for (CandidateHolder holder : outcome.candidates) {
            // audit L-8: the candidate is built once per holder - the constraint
            // checks are read-only, so rebuilding it per constraint is waste
            ExecutionCandidate built = holder.builder.build();
            HardConstraint.ConstraintContext context = new HardConstraint.ConstraintContext(
                    holder.descriptor, holder.probe, inputs.environment, inputs.hardware, request,
                    resolved.descriptor(), inputs.offline, inputs.remoteAllowed);
            PlanReason violation = null;
            for (HardConstraint constraint : constraints) {
                violation = constraint.check(built, context);
                if (violation != null) {
                    break;
                }
            }
            if (violation == null) {
                survivors.add(holder);
            } else {
                outcome.rejected.add(new SupportReport.RejectedCandidate(built, violation));
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
                // policies may mutate the builder's score, so the snapshot is
                // taken per policy (semantics preserved); the rejection path
                // reuses the same snapshot instead of building twice
                ExecutionCandidate built = holder.builder.build();
                PolicyDecision decision = policy.evaluate(built, planningContext);
                if (decision == null) {
                    continue;
                }
                if (decision.isRejection()) {
                    outcome.rejected.add(new SupportReport.RejectedCandidate(built, decision.asReason()));
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
            // audit 2026-10-07 (#146): one snapshot build per candidate -
            // rules used to rebuild (notes-map copy included) on every read
            ExecutionCandidate snapshot = holder.builder.build();
            for (ScoringRules.ScoringRule rule : scoringRules) {
                rule.apply(holder.builder, snapshot, holder.descriptor, request, inputs);
            }
        }
        // audit L-8: each candidate is built once; the comparator compares
        // precomputed keys instead of rebuilding candidates O(n log n) times
        List<ScoredCandidate> scored = new ArrayList<ScoredCandidate>(outcome.candidates.size());
        for (CandidateHolder holder : outcome.candidates) {
            ExecutionCandidate built = holder.builder.build();
            scored.add(new ScoredCandidate(built));
        }
        scored.sort(new Comparator<ScoredCandidate>() {
            @Override
            public int compare(ScoredCandidate a, ScoredCandidate b) {
                int byScore = Double.compare(b.score, a.score);
                if (byScore != 0) {
                    return byScore;
                }
                return a.stableKey.compareTo(b.stableKey);
            }
        });
        for (ScoredCandidate entry : scored) {
            outcome.viable.add(entry.candidate);
        }
    }

    private static final class ScoredCandidate {
        final ExecutionCandidate candidate;
        final double score;
        final String stableKey;

        ScoredCandidate(ExecutionCandidate candidate) {
            this.candidate = candidate;
            this.score = candidate.score();
            this.stableKey = candidate.stableKey();
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
            if (!request.isRepositoryAllowed(repository.id().value())) {
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
                return captured.tokenSupplier;
            }

            @Override
            public java.util.concurrent.ExecutorService executor() {
                return captured.executor;
            }

            @Override
            public io.cortexj.observability.PlatformEventListener eventListener() {
                return captured.eventListener;
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

    private static java.util.Map<String, ResolvedArtifact> artifactIndex(ResolvedModel resolved) {
        java.util.Map<String, ResolvedArtifact> index =
                new java.util.LinkedHashMap<String, ResolvedArtifact>();
        if (resolved.artifacts() != null) {
            for (ResolvedArtifact artifact : resolved.artifacts()) {
                if (artifact != null && artifact.artifact() != null
                        && !index.containsKey(artifact.artifact().id())) {
                    index.put(artifact.artifact().id(), artifact);
                }
            }
        }
        return index;
    }

    private List<PlanDownload> downloadsFor(ExecutionCandidate candidate, ResolvedModel resolved,
                                            java.util.Map<String, ResolvedArtifact> artifactIndex) {
        if (!candidate.requiresDownload() || candidate.artifact() == null) {
            return Collections.emptyList();
        }
        ResolvedArtifact artifact = artifactIndex.get(candidate.artifact().id());
        if (artifact == null) {
            return Collections.emptyList();
        }
        return PlanDownload.singleton(new PlanDownload(
                artifact.artifact().id(),
                artifact.location() == null ? resolved.repository().value() : artifact.location().toString(),
                artifact.artifact().sizeBytes()));
    }

    private ArtifactInspectionHolder inspectFor(ResolvedModel resolved, ModelArtifact artifact) {
        for (ResolvedArtifact resolvedArtifact : resolved.artifacts()) {
            if (resolvedArtifact.artifact().id().equals(artifact.id())) {
                io.cortexj.spi.artifact.ArtifactInspection inspection =
                        inputs.inspectIfLocal(resolved, resolvedArtifact);
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

    private static String probeHealthOf(RuntimeProbeResult probe) {
        if (probe == null || probe.health() == null) {
            return "UNKNOWN";
        }
        return probe.health().name();
    }

    private static String mostUsefulRemediation(Outcome outcome) {
        if (!outcome.rejected.isEmpty()) {
            // audit 2026-10-10: the old code returned whichever rejection
            // sorted first (usually a format mismatch) instead of the
            // actionable infrastructure cause - prefer those explicitly
            for (SupportReport.RejectedCandidate rejected : outcome.rejected) {
                ReasonCode code = rejected.reason().code();
                if (code == ReasonCode.RUNTIME_UNAVAILABLE
                        || code == ReasonCode.RUNTIME_MISCONFIGURED
                        || code == ReasonCode.MEMORY_LIMIT_EXCEEDED
                        || code == ReasonCode.MEMORY_INSUFFICIENT) {
                    return rejected.reason().message();
                }
            }
            return outcome.rejected.get(0).reason().message();
        }
        return "Install a runtime provider, configure a remote runtime, or check the model reference.";
    }

    private static void attachRuntimeHealth(PlanningException exception, Outcome outcome) {
        StringBuilder health = new StringBuilder();
        for (String line : outcome.runtimeHealthLines) {
            if (line == null || line.isEmpty()) {
                continue;
            }
            if (health.length() > 0) {
                health.append("; ");
            }
            health.append(line);
        }
        if (health.length() > 0) {
            exception.detail("runtimeHealth", health.toString());
        }
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
