package io.cortexj.planner.test;

import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelArtifact;
import io.cortexj.model.ModelDescriptor;
import io.cortexj.model.ModelReference;
import io.cortexj.model.TaskCapability;
import io.cortexj.observability.PlatformEventListener;
import io.cortexj.planner.ExecutionPlan;
import io.cortexj.planner.ExecutionRequest;
import io.cortexj.planner.internal.DefaultExecutionPlanner;
import io.cortexj.planner.internal.PlannerInputs;
import io.cortexj.runtime.DeviceType;
import io.cortexj.spi.conversion.ModelConverter;
import io.cortexj.spi.policy.ExecutionPolicy;
import io.cortexj.spi.repository.ModelRepositoryProvider;
import io.cortexj.spi.repository.RepositoryId;
import io.cortexj.spi.repository.ResolvedArtifact;
import io.cortexj.spi.repository.ResolvedModel;
import io.cortexj.spi.repository.ResolutionContext;
import io.cortexj.spi.repository.TokenSupplier;
import io.cortexj.spi.runtime.RuntimeProvider;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The spec §124.3 KPI, measured on the curated compatibility matrix:
 * "planner valid-plan rate on curated compatibility matrix: ≥ 99%".
 *
 * <p>The matrix lives in {@code compatibility/matrix.csv} (a versioned repo
 * artifact, not a generated file) and lists every runtime the platform harness
 * can serve for each model/artifact combination, bounded by each runtime's
 * documented Java floor (jinfer=25, libtorch=21). This test asserts:
 *
 * <ul>
 *   <li>a viable combination MUST produce a plan selecting a viable runtime;</li>
 *   <li>a combination with no viable runtime (or no resolvable artifact) MUST
 *       fail loudly - never a silent plan with a bogus candidate;</li>
 *   <li>the overall handled rate must be ≥ 99% (the spec's target).
 * </ul>
 *
 * The measured rate is printed so docs cite a number CI reproduces.
 */
class CuratedCompatibilityMatrixTest {

    private static final int MIN_JAVA_JINFER = 25;
    private static final int MIN_JAVA_LIBTORCH = 21;

    @Test
    void curatedMatrixPlansAtSpecRate() throws Exception {
        final List<String[]> rows = readMatrixRows();
        final DefaultExecutionPlanner planner =
                new DefaultExecutionPlanner(curatedInputs(rows));
        final int jdk = javaFeatureVersion();

        int planned = 0;
        int loudlyFailed = 0;
        final List<String> defects = new ArrayList<String>();

        for (String[] row : rows) {
            final String modelId = row[0];
            final Set<String> available = availableRuntimes(row[2], jdk);
            try {
                ExecutionPlan plan = planner.plan(
                        ExecutionRequest.forModel(ModelReference.of(modelId)));
                String selected = plan.selected() == null ? null : plan.selected().runtimeId();
                if (available.isEmpty()) {
                    defects.add(modelId + ": planned with no viable runtime (selected="
                            + selected + ")");
                } else if (selected == null || !available.contains(selected)) {
                    defects.add(modelId + ": selected=" + selected + " not in " + available);
                } else {
                    planned++;
                }
            } catch (RuntimeException loud) {
                if (available.isEmpty()) {
                    loudlyFailed++; // expected loud failure: nothing can serve it
                } else {
                    defects.add(modelId + ": loud failure though viable " + available
                            + " existed (" + loud.getMessage() + ")");
                }
            }
        }

        final int total = rows.size();
        final int ok = planned + loudlyFailed;
        System.out.println("curated matrix: " + ok + "/" + total
                + " handled correctly (planned=" + planned + ", loud-failures="
                + loudlyFailed + ", jdk=" + jdk + ")");

        assertTrue(defects.isEmpty(), "planner defects on curated matrix: " + defects);
        final double rate = (double) ok / (double) total;
        assertTrue(rate >= 0.99, "valid-plan rate " + rate
                + " below the §124.3 target of 0.99");
    }

    private int javaFeatureVersion() {
        String version = System.getProperty("java.version");
        if (version.startsWith("1.")) {
            return Integer.parseInt(version.split("\\.")[1]);
        }
        return Integer.parseInt(version.split("\\.")[0]);
    }

    private Set<String> availableRuntimes(String viableField, int jdk) {
        final Set<String> available = new LinkedHashSet<String>();
        if (viableField.trim().isEmpty()) {
            return available;
        }
        for (String runtime : viableField.split("\\|")) {
            if (minJavaOf(runtime.trim()) <= jdk) {
                available.add(runtime.trim());
            }
        }
        return available;
    }

    private int minJavaOf(String runtime) {
        if ("jinfer".equals(runtime)) {
            return MIN_JAVA_JINFER;
        }
        if ("libtorch".equals(runtime)) {
            return MIN_JAVA_LIBTORCH;
        }
        if ("llamacpp".equals(runtime)
                || "onnx".equals(runtime)
                || "remote-openai".equals(runtime)) {
            return 8;
        }
        throw new IllegalArgumentException("unknown runtime in matrix: " + runtime);
    }

    private List<String[]> readMatrixRows() throws IOException {
        final List<String[]> rows = new ArrayList<String[]>();
        InputStream in = CuratedCompatibilityMatrixTest.class.getResourceAsStream(
                "/compatibility/matrix.csv");
        if (in == null) {
            throw new IllegalStateException("compatibility/matrix.csv missing from classpath");
        }
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                // model,artifact,viable_runtimes (viable field may be empty)
                String[] parts = trimmed.split(",", 3);
                if (parts.length < 3) {
                    String[] padded = new String[3];
                    padded[0] = parts[0];
                    padded[1] = parts.length > 1 ? parts[1] : "";
                    padded[2] = "";
                    parts = padded;
                }
                rows.add(parts);
            }
        } finally {
            reader.close();
        }
        return rows;
    }

    /** Harness mirroring the shipped runtimes' declared descriptors. */
    private PlannerInputs curatedInputs(List<String[]> rows) {
        final int jdk = javaFeatureVersion();
        List<RuntimeProvider> providers = new ArrayList<RuntimeProvider>();
        providers.add(new TestRuntimeProvider("llamacpp", ArtifactFormat.GGUF)
                .devices(DeviceType.CPU)
                .task(TaskCapability.TEXT_GENERATION)
                .task(TaskCapability.EMBEDDING)
                .maturity(io.cortexj.runtime.RuntimeDescriptor.Maturity.STABLE));
        providers.add(new TestRuntimeProvider("onnx", ArtifactFormat.ONNX)
                .devices(DeviceType.CPU)
                .task(TaskCapability.TEXT_GENERATION)
                .maturity(io.cortexj.runtime.RuntimeDescriptor.Maturity.STABLE));
        providers.add(new TestRuntimeProvider("jinfer", ArtifactFormat.GGUF)
                .devices(DeviceType.CPU)
                .task(TaskCapability.TEXT_GENERATION)
                .task(TaskCapability.EMBEDDING)
                .task(TaskCapability.RERANKING)
                .minJava(MIN_JAVA_JINFER)
                .maturity(io.cortexj.runtime.RuntimeDescriptor.Maturity.STABLE));
        providers.add(new TestRuntimeProvider("remote-openai", ArtifactFormat.REMOTE)
                .devices(DeviceType.CPU)
                .task(TaskCapability.TEXT_GENERATION)
                .maturity(io.cortexj.runtime.RuntimeDescriptor.Maturity.STABLE));
        providers.add(new TestRuntimeProvider("libtorch", ArtifactFormat.SAFETENSORS)
                .devices(DeviceType.CPU)
                .task(TaskCapability.TEXT_GENERATION)
                .minJava(MIN_JAVA_LIBTORCH)
                .maturity(io.cortexj.runtime.RuntimeDescriptor.Maturity.BETA));

        ModelArtifact[] models = curatedModels(rows);
        return new PlannerInputs(
                providers,
                Arrays.<ModelRepositoryProvider>asList(
                        new ScopedTestRepositoryProvider(models)),
                new ArrayList<ModelConverter>(),
                new ArrayList<ExecutionPolicy>(),
                null,
                TestRepositoryProvider.environment(jdk),
                TestRepositoryProvider.hardware(false),
                null, null, null, null, false, true, "0.1.0-test", 0L);
    }

    /**
     * Only rows with a viable runtime get a serving artifact; rows whose
     * expected outcome is a loud failure stay unresolvable, like a real
     * repository that does not carry the artifact.
     */
    private ModelArtifact[] curatedModels(List<String[]> rows) {
        List<ModelArtifact> models = new ArrayList<ModelArtifact>();
        for (String[] row : rows) {
            if (row[2].trim().isEmpty()) {
                continue;
            }
            ArtifactFormat format =
                    ArtifactFormat.valueOf(row[1].trim().toUpperCase(Locale.ROOT));
            String artifactName = row[0] + artifactSuffix(format);
            models.add(TestRuntimeProvider.artifact(
                    artifactName, format, 4_000_000_000L, "q4"));
        }
        return models.toArray(new ModelArtifact[0]);
    }

    private static String artifactSuffix(ArtifactFormat format) {
        switch (format) {
            case GGUF:
                return ".gguf";
            case ONNX:
                return ".onnx";
            case SAFETENSORS:
                return ".safetensors";
            case REMOTE:
                return ".remote";
            default:
                return ".json";
        }
    }

    /** A repository provider that scopes artifacts to the requested model id. */
    static final class ScopedTestRepositoryProvider implements ModelRepositoryProvider {

        private final RepositoryId id = RepositoryId.of("curated-test-repo");
        private final List<ModelArtifact> artifacts = new ArrayList<ModelArtifact>();

        ScopedTestRepositoryProvider(ModelArtifact... models) {
            this.artifacts.addAll(Arrays.asList(models));
        }

        @Override
        public RepositoryId id() {
            return id;
        }

        @Override
        public boolean supports(ModelReference reference) {
            return !matching(reference).isEmpty();
        }

        @Override
        public ResolvedModel resolve(ModelReference reference,
                ResolutionContext context) {
            List<ModelArtifact> matching = matching(reference);
            if (matching.isEmpty()) {
                return null;
            }
            ModelDescriptor.Builder builder = ModelDescriptor.builder(reference.value())
                    .revision("curated-1")
                    .task(TaskCapability.TEXT_GENERATION);
            for (ModelArtifact artifact : matching) {
                builder.artifact(artifact);
            }
            final List<ResolvedArtifact> resolved = new ArrayList<ResolvedArtifact>();
            for (final ModelArtifact artifact : matching) {
                resolved.add(remoteAt("https://example.com/" + artifact.id(), artifact));
            }
            return new ResolvedModel(builder.build(), "curated-1", id, resolved);
        }

        private List<ModelArtifact> matching(ModelReference reference) {
            List<ModelArtifact> matching = new ArrayList<ModelArtifact>();
            String wanted = reference.value();
            for (ModelArtifact artifact : artifacts) {
                if (artifact.id().startsWith(wanted)) {
                    matching.add(artifact);
                }
            }
            return matching;
        }

        private static ResolvedArtifact remoteAt(final String uri, final ModelArtifact artifact) {
            return new ResolvedArtifact() {
                @Override
                public ModelArtifact artifact() {
                    return artifact;
                }

                @Override
                public URI location() {
                    return URI.create(uri);
                }

                @Override
                public java.util.Map<String, String> downloadHeaders() {
                    return Collections.emptyMap();
                }
            };
        }
    }
}
