package io.cortexj.planner.policy;

import io.cortexj.planner.ExecutionCandidate;
import io.cortexj.planner.ReasonCode;
import io.cortexj.spi.policy.ExecutionPolicy;
import io.cortexj.spi.policy.PlanningContext;
import io.cortexj.spi.policy.PolicyDecision;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * Centrally supplied enterprise runtime policy. Ops drops a
 * properties file into the platform working directory (or points
 * {@code cortexj.enterprise.policy} at one) and the planner enforces it on
 * every plan without code changes.
 *
 * <p>Supported keys (all optional, absent = unrestricted):
 * <ul>
 *   <li>{@code forbiddenRuntimes} - comma-separated runtime ids to reject</li>
 *   <li>{@code allowDownloads} - {@code false} rejects candidates that require
 *       a download (cache/local-only operation)</li>
 *   <li>{@code approvedLicenses} - comma-separated whitelist; artifacts without
 *       an approved license are rejected</li>
 *   <li>{@code requireVerifiedArtifacts} - {@code true} rejects artifacts
 *       without a declared checksum</li>
 *   <li>{@code allowGpu} - {@code false} rejects non-CPU devices</li>
 *   <li>{@code allowRemote} - {@code false} rejects remote runtimes</li>
 *   <li>{@code remoteRuntimes} - comma-separated remote runtime ids
 *       (default: {@code openai-remote})</li>
 * </ul>
 */
public final class EnterpriseRuntimePolicy implements ExecutionPolicy {

    public static final String POLICY_ID = "enterprise";
    public static final String FILE_NAME = "enterprise-policy.properties";
    public static final String SYSPROP = "cortexj.enterprise.policy";

    private final Set<String> forbiddenRuntimes;
    private final boolean allowDownloads;
    private final Set<String> approvedLicenses;
    private final boolean requireVerifiedArtifacts;
    private final boolean allowGpu;
    private final boolean allowRemote;
    private final Set<String> remoteRuntimes;

    private EnterpriseRuntimePolicy(Set<String> forbiddenRuntimes,
                                    boolean allowDownloads,
                                    Set<String> approvedLicenses,
                                    boolean requireVerifiedArtifacts,
                                    boolean allowGpu,
                                    boolean allowRemote,
                                    Set<String> remoteRuntimes) {
        this.forbiddenRuntimes = forbiddenRuntimes;
        this.allowDownloads = allowDownloads;
        this.approvedLicenses = approvedLicenses;
        this.requireVerifiedArtifacts = requireVerifiedArtifacts;
        this.allowGpu = allowGpu;
        this.allowRemote = allowRemote;
        this.remoteRuntimes = remoteRuntimes;
    }

    public static EnterpriseRuntimePolicy unrestricted() {
        return new EnterpriseRuntimePolicy(Collections.<String>emptySet(), true,
                Collections.<String>emptySet(), false, true, true,
                Collections.singleton("openai-remote"));
    }

    public static EnterpriseRuntimePolicy fromFile(Path file) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        }
        return fromProperties(properties);
    }

    public static EnterpriseRuntimePolicy fromProperties(Properties properties) {
        return new EnterpriseRuntimePolicy(
                csvLower(properties.getProperty("forbiddenRuntimes")),
                flag(properties, "allowDownloads", true),
                csvLower(properties.getProperty("approvedLicenses")),
                flag(properties, "requireVerifiedArtifacts", false),
                flag(properties, "allowGpu", true),
                flag(properties, "allowRemote", true),
                remoteRuntimes(properties));
    }

    public boolean isRestrictive() {
        return !forbiddenRuntimes.isEmpty()
                || !allowDownloads
                || !approvedLicenses.isEmpty()
                || requireVerifiedArtifacts
                || !allowGpu
                || !allowRemote;
    }

    @Override
    public String policyId() {
        return POLICY_ID;
    }

    @Override
    public PolicyDecision evaluate(ExecutionCandidate candidate, PlanningContext context) {
        String runtimeId = candidate.runtimeId();
        if (forbiddenRuntimes.contains(lower(runtimeId))) {
            return PolicyDecision.reject(ReasonCode.POLICY_APPLIED,
                    "runtime '" + runtimeId + "' is forbidden by the enterprise runtime policy");
        }
        if (!allowRemote && remoteRuntimes.contains(lower(runtimeId))) {
            return PolicyDecision.reject(ReasonCode.POLICY_APPLIED,
                    "remote execution is forbidden by the enterprise runtime policy "
                            + "(runtime '" + runtimeId + "')");
        }
        if (!allowGpu && candidate.device() != io.cortexj.runtime.DeviceType.CPU) {
            return PolicyDecision.reject(ReasonCode.POLICY_APPLIED,
                    "GPU execution is forbidden by the enterprise runtime policy "
                            + "(candidate device " + candidate.device() + ")");
        }
        if (!allowDownloads && candidate.requiresDownload()) {
            return PolicyDecision.reject(ReasonCode.POLICY_APPLIED,
                    "external downloads are forbidden by the enterprise runtime policy; "
                            + "pull the artifact into the cache first (cortexj pull) or use a local model");
        }
        if (requireVerifiedArtifacts
                && (candidate.artifact() == null || candidate.artifact().checksum() == null)) {
            return PolicyDecision.reject(ReasonCode.POLICY_APPLIED,
                    "unverified artifacts are forbidden by the enterprise runtime policy; "
                            + "artifact '" + (candidate.artifact() == null
                            ? "<none>" : candidate.artifact().id())
                            + "' declares no checksum");
        }
        if (!approvedLicenses.isEmpty()) {
            String license = lower(candidate.license());
            if (license == null || license.isEmpty()) {
                return PolicyDecision.reject(ReasonCode.POLICY_APPLIED,
                        "artifact '" + candidate.artifact().id()
                                + "' declares no license; the enterprise runtime policy only "
                                + "approves " + approvedLicenses);
            }
            if (!approvedLicenses.contains(license)) {
                return PolicyDecision.reject(ReasonCode.POLICY_APPLIED,
                        "license '" + candidate.license() + "' is not on the enterprise "
                                + "approved list " + approvedLicenses);
            }
        }
        return PolicyDecision.allow();
    }

    private static Set<String> remoteRuntimes(Properties properties) {
        Set<String> configured = csvLower(properties.getProperty("remoteRuntimes"));
        if (configured.isEmpty()) {
            return Collections.singleton("openai-remote");
        }
        return configured;
    }

    private static boolean flag(Properties properties, String key, boolean fallback) {
        String raw = properties.getProperty(key);
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        return Boolean.parseBoolean(raw.trim());
    }

    private static Set<String> csvLower(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> values = new LinkedHashSet<String>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                values.add(lower(trimmed));
            }
        }
        return Collections.unmodifiableSet(values);
    }

    private static String lower(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
