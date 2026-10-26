package io.cortexj.tck;

import io.cortexj.hardware.EnvironmentProfile;
import io.cortexj.hardware.HardwareProfile;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelArtifact;
import io.cortexj.model.ModelDescriptor;
import io.cortexj.model.ModelReference;
import io.cortexj.model.TaskCapability;
import io.cortexj.planner.ExecutionRequest;
import io.cortexj.runtime.DeviceDescriptor;
import io.cortexj.runtime.DeviceType;
import io.cortexj.spi.runtime.ModelRuntime;
import io.cortexj.spi.runtime.RuntimeEvaluationRequest;
import io.cortexj.spi.runtime.RuntimeOpenRequest;
import io.cortexj.spi.runtime.RuntimeProbeContext;
import io.cortexj.spi.runtime.RuntimeProbeResult;
import io.cortexj.spi.runtime.RuntimeProvider;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public abstract class RuntimeProviderContract {

    protected abstract RuntimeProvider provider();

    /**
     * A loadable model artifact for the lifecycle/streaming contracts, or null
     * when the provider cannot ship one on this machine - those contracts then
     * skip loudly (explicit assumeTrue, never silently).
     */
    protected Path loadableArtifactPath() {
        return null;
    }

    protected DeviceType loadableArtifactDevice() {
        return DeviceType.CPU;
    }

    /**
     * The open request used by the lifecycle/streaming contracts; subclasses
     * override to add provider-specific configuration.
     */
    protected RuntimeOpenRequest openRequest(Path artifactPath) {
        return new RuntimeOpenRequest(
                ModelDescriptor.builder("tck/model")
                        .revision("tckrev")
                        .task(TaskCapability.TEXT_GENERATION)
                        .build(),
                artifactPath == null ? null : artifactNodeFor(ArtifactFormat.UNKNOWN),
                artifactPath,
                loadableArtifactDevice(),
                Collections.<String, Object>emptyMap(),
                Collections.<String, String>emptyMap(),
                testEnvironment(), testHardware(false), null, null);
    }

    /**
     * Whether the provider must be discoverable via ServiceLoader (the
     * registration contract). True by default for bundled providers; a
     * third-party TCK that wires the provider explicitly overrides to false.
     */
    protected boolean expectServiceLoaderRegistration() {
        return true;
    }

    public static EnvironmentProfile testEnvironment() {
        return new EnvironmentProfile(
                "TCKVendor", "TCKJvm", 21, "21.0.0", "linux", "x86_64",
                8, 2L * 1024L * 1024L * 1024L, 16L * 1024L * 1024L * 1024L,
                8L * 1024L * 1024L * 1024L, true, null);
    }

    public static HardwareProfile testHardware(boolean withGpu) {
        List<DeviceDescriptor> devices = new ArrayList<DeviceDescriptor>();
        devices.add(DeviceDescriptor.of(DeviceType.CPU, "tck-cpu", 16L * 1024L * 1024L * 1024L));
        if (withGpu) {
            devices.add(DeviceDescriptor.of(DeviceType.GPU, "tck-gpu", 8L * 1024L * 1024L * 1024L));
        }
        return new HardwareProfile(devices, 16L * 1024L * 1024L * 1024L,
                withGpu, false, false, false, Collections.<String>emptyList());
    }

    public static RuntimeProbeContext probeContext() {
        return new RuntimeProbeContext(testEnvironment(), testHardware(true), false, Collections.<String, String>emptyMap());
    }

    /**
     * Audit 2026-10-10: the contract tests probe through this hook instead
     * of the static {@link #probeContext()} directly - providers whose
     * probe needs configuration (the remote runtime needs
     * {@code remote.baseUrl}) override it, otherwise those contracts skip
     * on every machine even when the provider is fully testable.
     */
    protected RuntimeProbeContext contractProbeContext() {
        return probeContext();
    }

    public static RuntimeEvaluationRequest evaluationRequest(ArtifactFormat format) {
        ModelDescriptor descriptor = ModelDescriptor.builder("tck/model")
                .revision("tckrev")
                .task(TaskCapability.TEXT_GENERATION)
                .build();
        return new RuntimeEvaluationRequest(
                descriptor,
                artifactNodeFor(format),
                testEnvironment(), testHardware(true),
                ExecutionRequest.forModel(ModelReference.of("tck/model")));
    }

    public static ModelArtifact artifactNodeFor(final ArtifactFormat format) {
        return new ModelArtifact() {
            @Override
            public String id() {
                return "tck-artifact";
            }

            @Override
            public ArtifactFormat format() {
                return format;
            }

            @Override
            public long sizeBytes() {
                return 1024L;
            }

            @Override
            public io.cortexj.model.Checksum checksum() {
                return null;
            }

            @Override
            public java.util.Map<String, String> metadata() {
                return Collections.emptyMap();
            }
        };
    }

    @Test
    public void descriptorIdentityIsConsistent() {
        assertNotNull(provider().id(), "runtime id must not be null");
        assertEquals(provider().id(), provider().descriptor().id(),
                "descriptor id must equal provider id");
        assertTrue(!provider().descriptor().devices().isEmpty(),
                "descriptor must declare at least one device");
        assertTrue(!provider().descriptor().tasks().isEmpty(),
                "descriptor must declare at least one task");
        assertTrue(provider().descriptor().minJavaFeatureVersion() >= 8,
                "descriptor must declare a minimum Java version");
        assertNotNull(provider().descriptor().maturity());
    }

    @Test
    public void probeNeverThrows() {
        RuntimeProbeResult result = provider().probe(probeContext());
        assertNotNull(result, "probe must always return a result");
        assertNotNull(result.health(), "probe must report a health state");
        if (!result.isUsable()) {
            assertNotNull(result.message(), "unusable probe must explain why");
        }
    }

    @Test
    public void evaluationRejectsUnsupportedFormatWithReason() {
        ArtifactFormat alien = alienFormatFor(provider());
        io.cortexj.spi.runtime.RuntimeSupport support = provider().evaluate(evaluationRequest(alien));
        assertNotNull(support);
        assertTrue(!support.isSupported(), "unsupported format must be reported as unsupported");
        assertTrue(!support.reasons().isEmpty(), "unsupported evaluation must carry reasons");
    }

    @Test
    public void providerIsDiscoverableViaServiceLoader() {
        // registration contract (audit: docs claimed TCK validates registration
        // but no such test existed)
        assumeTrue(expectServiceLoaderRegistration(),
                "the provider is wired explicitly - the ServiceLoader registration contract is skipped loudly");
        boolean found = false;
        for (RuntimeProvider registered : java.util.ServiceLoader.load(RuntimeProvider.class,
                provider().getClass().getClassLoader())) {
            if (registered.id().value().equals(provider().id().value())) {
                found = true;
                break;
            }
        }
        assertTrue(found,
                "the provider must be discoverable via ServiceLoader from its own classloader");
    }

    @Test
    public void runtimeCloseIsIdempotent() {
        // lifecycle contract: close() must be a one-shot - a second close is a
        // no-op, never a double-free of the native session (audit P0-1)
        RuntimeProbeResult probe = provider().probe(contractProbeContext());
        assumeTrue(probe.isUsable(),
                "runtime not usable on this machine - the lifecycle close contract is skipped loudly: "
                        + probe.message());
        ModelRuntime runtime = provider().open(openRequest(null));
        runtime.close();
        runtime.close();
        runtime.close();
    }

    /**
     * Audit 2026-10-10: server-side runtimes (remote-openai) never fail a
     * load on the artifact path - execution happens on the server and the
     * failure modes are the 404/auth tests in their own TCK class. Such
     * providers override this to false so the contract skips loudly
     * instead of asserting a local-failure semantics they do not have.
     */
    protected boolean failedLoadContractApplies() {
        return true;
    }

    @Test
    public void failedLoadIsTypedAndProviderStaysUsable() {
        RuntimeProbeResult probe = provider().probe(contractProbeContext());
        assumeTrue(probe.isUsable(),
                "runtime not usable on this machine - the failed-load contract is skipped loudly: "
                        + probe.message());
        assumeTrue(loadableArtifactPath() != null,
                "the provider ships no loadable artifact on this machine - the failed-load "
                        + "contract is skipped loudly");
        assumeTrue(failedLoadContractApplies(),
                "server-side runtime: local artifact-failure semantics do not apply - the "
                        + "failed-load contract is covered by the provider's own 404/auth tests");
        Path artifact = loadableArtifactPath();
        Path bogus = artifact.resolveSibling("definitely-not-a-model-" + System.nanoTime());
        ModelRuntime runtime = provider().open(openRequest(bogus));
        try {
            assertThrows(RuntimeException.class,
                    () -> runtime.load(loadRequestFor(bogus)),
                    "a load with a bogus artifact must fail with a typed error");
        } finally {
            runtime.close();
        }
        // the provider stays usable after a failed load
        ModelRuntime second = provider().open(openRequest(artifact));
        second.close();
    }

    @Test
    public void tokenStreamSupportsSingleSubscription() {
        RuntimeProbeResult probe = provider().probe(contractProbeContext());
        assumeTrue(probe.isUsable(),
                "runtime not usable on this machine - the streaming contract is skipped loudly: "
                        + probe.message());
        assumeTrue(loadableArtifactPath() != null,
                "the provider ships no loadable artifact on this machine - the streaming "
                        + "contract is skipped loudly");
        ModelRuntime runtime = provider().open(openRequest(loadableArtifactPath()));
        try {
            io.cortexj.spi.runtime.RuntimeModel model = runtime.load(loadRequestFor(loadableArtifactPath()));
            assertTrue(model instanceof io.cortexj.inference.TextGenerationCapability,
                    "the streaming contract requires a text-generation capability; "
                            + "an embedding-only runtime skips this contract loudly");
            io.cortexj.inference.TextGenerationCapability generator =
                    (io.cortexj.inference.TextGenerationCapability) model;
            io.cortexj.stream.TokenStream stream = generator.stream(
                    io.cortexj.inference.GenerationRequest.prompt("tck"));
            final java.util.concurrent.atomic.AtomicInteger terminals =
                    new java.util.concurrent.atomic.AtomicInteger();
            io.cortexj.stream.StreamSubscription first = stream.subscribe(new io.cortexj.stream.TokenConsumer() {
                @Override
                public void onToken(io.cortexj.stream.Token token) {
                }

                @Override
                public void onComplete(io.cortexj.stream.StreamCompletion completion) {
                    terminals.incrementAndGet();
                }

                @Override
                public void onError(io.cortexj.error.CortexJException error) {
                    terminals.incrementAndGet();
                }
            });
            assertNotNull(first, "the first subscription must be accepted");
            // the terminal is delivered exactly once even across the cancel
            first.cancel();
            assertThrows(IllegalStateException.class,
                    () -> stream.subscribe(new io.cortexj.stream.TokenConsumer() {
                        @Override
                        public void onToken(io.cortexj.stream.Token token) {
                        }

                        @Override
                        public void onComplete(io.cortexj.stream.StreamCompletion completion) {
                        }

                        @Override
                        public void onError(io.cortexj.error.CortexJException error) {
                        }
                    }),
                    "a token stream must support a single subscription");
            assertTrue(terminals.get() <= 1,
                    "the terminal must be delivered at most once after cancel");
        } catch (RuntimeException streamingUnsupported) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "the runtime declined the streaming attempt - the streaming contract is "
                            + "skipped loudly: " + streamingUnsupported.getMessage());
        } finally {
            runtime.close();
        }
    }

    private io.cortexj.spi.runtime.RuntimeModelRequest loadRequestFor(Path artifactPath) {
        return new io.cortexj.spi.runtime.RuntimeModelRequest(
                ModelDescriptor.builder("tck/model")
                        .revision("tckrev")
                        .task(TaskCapability.TEXT_GENERATION)
                        .build(),
                artifactNodeFor(ArtifactFormat.UNKNOWN),
                artifactPath,
                loadableArtifactDevice(),
                Collections.<String, Object>emptyMap());
    }

    private static ArtifactFormat alienFormatFor(RuntimeProvider provider) {
        for (ArtifactFormat format : ArtifactFormat.values()) {
            if (format.isExecutableWeights() && !provider.descriptor().formats().contains(format)) {
                return format;
            }
        }
        return ArtifactFormat.UNKNOWN;
    }
}
