package io.cortexj.planner.policy;

import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelArtifact;
import io.cortexj.planner.ExecutionCandidate;
import io.cortexj.runtime.DeviceType;
import io.cortexj.spi.policy.PolicyDecision;
import io.cortexj.spi.policy.PlanningContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnterpriseRuntimePolicyTest {

    private static final ModelArtifact ARTIFACT = new ModelArtifact.Abstract(
            "model.gguf", ArtifactFormat.GGUF, 1024L, null, null) {
    };

    private PolicyDecision evaluate(EnterpriseRuntimePolicy policy, ExecutionCandidate candidate) {
        return policy.evaluate(candidate, null);
    }

    private ExecutionCandidate candidate(String runtime, DeviceType device, boolean requiresDownload,
                                         String license, ModelArtifact artifact) {
        return ExecutionCandidate.builder(runtime)
                .artifact(artifact)
                .device(device)
                .requiresDownload(requiresDownload)
                .license(license)
                .build();
    }

    @Test
    void unrestrictedPolicyAllowsEverything() {
        EnterpriseRuntimePolicy policy = EnterpriseRuntimePolicy.unrestricted();
        assertTrue(evaluate(policy, candidate("llamacpp", DeviceType.GPU, true, null, null)).outcome()
                == PolicyDecision.Outcome.ALLOW);
        assertTrue(evaluate(policy, candidate("openai-remote", DeviceType.CPU, false, "unknown", ARTIFACT)).outcome()
                == PolicyDecision.Outcome.ALLOW);
    }

    @Test
    void forbiddenRuntimesAreRejectedCaseInsensitively() {
        Properties properties = new Properties();
        properties.setProperty("forbiddenRuntimes", "LLamaCpp, djl");
        EnterpriseRuntimePolicy policy = EnterpriseRuntimePolicy.fromProperties(properties);
        assertTrue(policy.isRestrictive());
        assertTrue(evaluate(policy, candidate("llamacpp", DeviceType.CPU, false, null, null)).isRejection());
        assertTrue(evaluate(policy, candidate("djl", DeviceType.CPU, false, null, null)).isRejection());
        assertFalse(evaluate(policy, candidate("onnx", DeviceType.CPU, false, null, null)).isRejection());
        assertTrue(evaluate(policy, candidate("llamacpp", DeviceType.CPU, false, null, null)).message()
                .contains("forbidden by the enterprise runtime policy"));
    }

    @Test
    void remoteExecutionForbiddenByDefaultRemoteSet() {
        Properties properties = new Properties();
        properties.setProperty("allowRemote", "false");
        EnterpriseRuntimePolicy policy = EnterpriseRuntimePolicy.fromProperties(properties);
        assertTrue(evaluate(policy, candidate("openai-remote", DeviceType.CPU, false, null, null)).isRejection());
        assertFalse(evaluate(policy, candidate("llamacpp", DeviceType.CPU, false, null, null)).isRejection());
    }

    @Test
    void remoteExecutionForbiddenWithCustomRuntimeSet() {
        Properties properties = new Properties();
        properties.setProperty("allowRemote", "false");
        properties.setProperty("remoteRuntimes", "vendor-cloud");
        EnterpriseRuntimePolicy policy = EnterpriseRuntimePolicy.fromProperties(properties);
        assertTrue(evaluate(policy, candidate("vendor-cloud", DeviceType.CPU, false, null, null)).isRejection());
        assertFalse(evaluate(policy, candidate("openai-remote", DeviceType.CPU, false, null, null)).isRejection());
    }

    @Test
    void gpuExecutionForbidden() {
        Properties properties = new Properties();
        properties.setProperty("allowGpu", "false");
        EnterpriseRuntimePolicy policy = EnterpriseRuntimePolicy.fromProperties(properties);
        assertTrue(evaluate(policy, candidate("llamacpp", DeviceType.GPU, false, null, null)).isRejection());
        assertFalse(evaluate(policy, candidate("llamacpp", DeviceType.CPU, false, null, null)).isRejection());
    }

    @Test
    void downloadsForbiddenButCachedCandidatesPass() {
        Properties properties = new Properties();
        properties.setProperty("allowDownloads", "false");
        EnterpriseRuntimePolicy policy = EnterpriseRuntimePolicy.fromProperties(properties);
        assertTrue(evaluate(policy, candidate("llamacpp", DeviceType.CPU, true, null, null)).isRejection());
        assertFalse(evaluate(policy, candidate("llamacpp", DeviceType.CPU, false, null, null)).isRejection());
    }

    @Test
    void unverifiedArtifactsForbidden() {
        Properties properties = new Properties();
        properties.setProperty("requireVerifiedArtifacts", "true");
        EnterpriseRuntimePolicy policy = EnterpriseRuntimePolicy.fromProperties(properties);
        assertTrue(evaluate(policy, candidate("llamacpp", DeviceType.CPU, false, null, ARTIFACT)).isRejection());
        ModelArtifact verified = new ModelArtifact.Abstract(
                "model.gguf", ArtifactFormat.GGUF, 1024L,
                io.cortexj.model.Checksum.of("sha256",
                        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"), null) {
        };
        assertFalse(evaluate(policy, candidate("llamacpp", DeviceType.CPU, false, null, verified)).isRejection());
        assertTrue(evaluate(policy, candidate("llamacpp", DeviceType.CPU, false, null, null)).isRejection());
    }

    @Test
    void approvedLicensesWhitelistRejectsUnknownAndMissing() {
        Properties properties = new Properties();
        properties.setProperty("approvedLicenses", "apache-2.0, mit");
        EnterpriseRuntimePolicy policy = EnterpriseRuntimePolicy.fromProperties(properties);
        assertFalse(evaluate(policy, candidate("llamacpp", DeviceType.CPU, false, "Apache-2.0", ARTIFACT))
                .isRejection());
        assertTrue(evaluate(policy, candidate("llamacpp", DeviceType.CPU, false, "gpl-3.0", ARTIFACT)).isRejection());
        assertTrue(evaluate(policy, candidate("llamacpp", DeviceType.CPU, false, null, ARTIFACT)).isRejection());
        assertTrue(evaluate(policy, candidate("llamacpp", DeviceType.CPU, false, "gpl-3.0", ARTIFACT)).message()
                .contains("not on the enterprise approved list"));
    }

    @Test
    void fromFileParsesProperties() throws IOException {
        Path file = Files.createTempFile("enterprise-policy", ".properties");
        try {
            Files.write(file, java.util.Arrays.asList(
                    "forbiddenRuntimes=llamacpp",
                    "allowDownloads=false"));
            EnterpriseRuntimePolicy policy = EnterpriseRuntimePolicy.fromFile(file);
            assertTrue(policy.isRestrictive());
            assertTrue(evaluate(policy, candidate("llamacpp", DeviceType.CPU, false, null, null)).isRejection());
            assertTrue(evaluate(policy, candidate("onnx", DeviceType.CPU, true, null, null)).isRejection());
            assertFalse(evaluate(policy, candidate("onnx", DeviceType.CPU, false, null, null)).isRejection());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void candidateCarriesLicenseThroughBuilder() {
        ExecutionCandidate withLicense = candidate("llamacpp", DeviceType.CPU, false, "mit", ARTIFACT);
        assertEquals("mit", withLicense.license());
        assertNull(candidate("llamacpp", DeviceType.CPU, false, null, null).license());
    }
}
