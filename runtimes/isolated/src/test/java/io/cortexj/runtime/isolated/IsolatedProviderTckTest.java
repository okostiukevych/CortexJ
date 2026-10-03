package io.cortexj.runtime.isolated;

import io.cortexj.tck.RuntimeProviderContract;
import org.junit.jupiter.api.BeforeAll;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The shared runtime-provider contract executed against the isolated
 * provider hosting the stub runtime in a real child JVM. The ServiceLoader
 * registration contract is skipped loudly: this provider is deliberately
 * programmatic-only (never auto-registered for all users).
 */
class IsolatedProviderTckTest extends RuntimeProviderContract {

    private static final IsolatedRuntimeProvider PROVIDER = new IsolatedRuntimeProvider("stub");
    private static Path artifact;

    @BeforeAll
    static void createLoadableArtifact() throws Exception {
        artifact = Files.createTempFile("isolated-tck-model", ".onnx");
        Files.write(artifact, "tck".getBytes(StandardCharsets.UTF_8));
    }

    @Override
    protected io.cortexj.spi.runtime.RuntimeProvider provider() {
        return PROVIDER;
    }

    @Override
    protected Path loadableArtifactPath() {
        return artifact;
    }

    @Override
    protected boolean expectServiceLoaderRegistration() {
        // opt-in provider: wired explicitly via
        // CortexJ.builder().runtimeProvider(new IsolatedRuntimeProvider(...))
        return false;
    }
}
