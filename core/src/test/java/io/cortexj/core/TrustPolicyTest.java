package io.cortexj.core;

import io.cortexj.CortexJ;
import io.cortexj.ErrorCode;
import io.cortexj.error.ModelLoadException;
import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelReference;
import io.cortexj.model.TrustPolicy;
import io.cortexj.observability.PlatformEvent;
import io.cortexj.observability.PlatformEventListener;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrustPolicyTest {

    static final class RecordingListener implements PlatformEventListener {

        final List<PlatformEvent> events = new ArrayList<PlatformEvent>();

        @Override
        public void onEvent(PlatformEvent event) {
            events.add(event);
        }
    }

    private CortexJ platform(TrustPolicy policy, RecordingListener listener) {
        return CortexJ.builder()
                .runtimeProvider(new io.cortexj.core.EndToEndLoadTest.FakeRuntimeProvider())
                .repositoryProvider(new io.cortexj.core.EndToEndLoadTest.FakeRepository())
                .eventListener(listener)
                .trustPolicy(policy)
                .build();
    }

    @Test
    void trustedOnlyBlocksUntrustedModelWithActionableError() {
        RecordingListener listener = new RecordingListener();
        CortexJ platform = platform(TrustPolicy.TRUSTED_ONLY, listener);
        try {
            ModelLoadException failure = assertThrows(ModelLoadException.class,
                    () -> platform.models().load(ModelReference.of("e2e/test-model")));
            assertEquals(ErrorCode.SEC_POLICY_BLOCKED, failure.code());
            assertTrue(!failure.remediation().isEmpty());
        } finally {
            platform.close();
        }
    }

    @Test
    void trustedOnlyBlocksLocalArtifactWithoutChecksumOrNote() {
        // audit 2026-10-07 (#143): the local provider never sets a trustNote,
        // so an absent note used to bypass TRUSTED_ONLY entirely
        RecordingListener listener = new RecordingListener();
        CortexJ platform = CortexJ.builder()
                .runtimeProvider(new io.cortexj.core.EndToEndLoadTest.FakeRuntimeProvider())
                .repositoryProvider(new LocalLikeRepository(false))
                .eventListener(listener)
                .trustPolicy(TrustPolicy.TRUSTED_ONLY)
                .build();
        try {
            ModelLoadException failure = assertThrows(ModelLoadException.class,
                    () -> platform.models().load(ModelReference.of("local/unchecked")));
            assertEquals(ErrorCode.SEC_POLICY_BLOCKED, failure.code());
        } finally {
            platform.close();
        }
    }

    @Test
    void trustedOnlyAllowsLocalArtifactWithChecksum() {
        RecordingListener listener = new RecordingListener();
        CortexJ platform = CortexJ.builder()
                .runtimeProvider(new io.cortexj.core.EndToEndLoadTest.FakeRuntimeProvider())
                .repositoryProvider(new LocalLikeRepository(true))
                .eventListener(listener)
                .trustPolicy(TrustPolicy.TRUSTED_ONLY)
                .build();
        try {
            ModelHandle handle = platform.models().load(ModelReference.of("local/pinned"));
            handle.close();
        } finally {
            platform.close();
        }
    }

    /** Imitates the local provider: never sets a trustNote, checksum optional. */
    static final class LocalLikeRepository implements io.cortexj.spi.repository.ModelRepositoryProvider {

        private final boolean withChecksum;

        LocalLikeRepository(boolean withChecksum) {
            this.withChecksum = withChecksum;
        }

        @Override
        public io.cortexj.spi.repository.RepositoryId id() {
            return io.cortexj.spi.repository.RepositoryId.of("local-like");
        }

        @Override
        public boolean supports(io.cortexj.model.ModelReference reference) {
            return true;
        }

        @Override
        public io.cortexj.spi.repository.ResolvedModel resolve(
                io.cortexj.model.ModelReference reference,
                io.cortexj.spi.repository.ResolutionContext context) {
            final io.cortexj.model.ModelArtifact artifact =
                    new io.cortexj.model.ModelArtifact.Abstract(
                            "model.gguf",
                            io.cortexj.model.ArtifactFormat.GGUF,
                            1_000_000L,
                            withChecksum
                                    ? io.cortexj.model.Checksum.sha256(
                                    "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08")
                                    : null,
                            java.util.Collections.<String, String>emptyMap()) {
                    };
            io.cortexj.model.ModelDescriptor descriptor = io.cortexj.model.ModelDescriptor
                    .builder(reference.value())
                    .revision("local-rev")
                    .architecture("llama")
                    .task(io.cortexj.model.TaskCapability.TEXT_GENERATION)
                    .artifact(artifact)
                    .build();
            java.util.List<io.cortexj.spi.repository.ResolvedArtifact> artifacts =
                    new java.util.ArrayList<io.cortexj.spi.repository.ResolvedArtifact>();
            artifacts.add(new io.cortexj.spi.support.SimpleResolvedArtifact(artifact,
                    java.net.URI.create("file:/tmp/definitely-missing.gguf"),
                    java.util.Collections.<String, String>emptyMap()));
            return new io.cortexj.spi.repository.ResolvedModel(descriptor, "local-rev",
                    io.cortexj.spi.repository.RepositoryId.of("local-like"), artifacts);
        }
    }

    @Test
    void allowUnsignedWithWarningLoadsAndRecordsEvent() {
        RecordingListener listener = new RecordingListener();
        CortexJ platform = platform(TrustPolicy.ALLOW_UNSIGNED_WITH_WARNING, listener);
        try {
            ModelHandle handle = platform.models().load(ModelReference.of("e2e/test-model"));
            handle.close();
            boolean warned = false;
            for (PlatformEvent event : listener.events) {
                if (event.level() == PlatformEvent.Level.WARNING
                        && event.message().contains("untrusted model")) {
                    warned = true;
                    break;
                }
            }
            assertTrue(warned, "a warning event must be recorded for untrusted models");
        } finally {
            platform.close();
        }
    }

    @Test
    void trustedOnlyBlocksUnverifiedWeightsEvenWhenTokenizerSidecarHasChecksum() {
        RecordingListener listener = new RecordingListener();
        CortexJ platform = CortexJ.builder()
                .runtimeProvider(new io.cortexj.core.EndToEndLoadTest.FakeRuntimeProvider())
                .repositoryProvider(new SidecarOnlyChecksumRepository("sha256"))
                .eventListener(listener)
                .trustPolicy(TrustPolicy.TRUSTED_ONLY)
                .build();
        try {
            ModelLoadException failure = assertThrows(ModelLoadException.class,
                    () -> platform.models().load(ModelReference.of("local/sidecar-only")));
            assertEquals(ErrorCode.SEC_POLICY_BLOCKED, failure.code());
        } finally {
            platform.close();
        }
    }

    @Test
    void trustedOnlyBlocksWeakChecksumAlgorithmOnWeights() {
        RecordingListener listener = new RecordingListener();
        CortexJ platform = CortexJ.builder()
                .runtimeProvider(new io.cortexj.core.EndToEndLoadTest.FakeRuntimeProvider())
                .repositoryProvider(new SidecarOnlyChecksumRepository("md5"))
                .eventListener(listener)
                .trustPolicy(TrustPolicy.TRUSTED_ONLY)
                .build();
        try {
            ModelLoadException failure = assertThrows(ModelLoadException.class,
                    () -> platform.models().load(ModelReference.of("local/weak-algo")));
            assertEquals(ErrorCode.SEC_POLICY_BLOCKED, failure.code());
        } finally {
            platform.close();
        }
    }

    static final class SidecarOnlyChecksumRepository implements io.cortexj.spi.repository.ModelRepositoryProvider {
        private final String mode;

        SidecarOnlyChecksumRepository(String mode) {
            this.mode = mode;
        }

        @Override
        public io.cortexj.spi.repository.RepositoryId id() {
            return io.cortexj.spi.repository.RepositoryId.of("sidecar-repo");
        }

        @Override
        public boolean supports(io.cortexj.model.ModelReference reference) {
            return true;
        }

        @Override
        public io.cortexj.spi.repository.ResolvedModel resolve(
                io.cortexj.model.ModelReference reference,
                io.cortexj.spi.repository.ResolutionContext context) {
            io.cortexj.model.Checksum weightChecksum = "md5".equals(mode)
                    ? io.cortexj.model.Checksum.of("md5", "d41d8cd98f00b204e9800998ecf8427e")
                    : null;
            final io.cortexj.model.ModelArtifact weights =
                    new io.cortexj.model.ModelArtifact.Abstract(
                            "model.gguf",
                            io.cortexj.model.ArtifactFormat.GGUF,
                            1_000_000L,
                            weightChecksum,
                            java.util.Collections.<String, String>emptyMap()) {
                    };
            final io.cortexj.model.ModelArtifact tokenizer =
                    new io.cortexj.model.ModelArtifact.Abstract(
                            "tokenizer.json",
                            io.cortexj.model.ArtifactFormat.TOKENIZER,
                            1024L,
                            io.cortexj.model.Checksum.sha256(
                                    "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"),
                            java.util.Collections.<String, String>emptyMap()) {
                    };
            io.cortexj.model.ModelDescriptor descriptor = io.cortexj.model.ModelDescriptor
                    .builder(reference.value())
                    .revision("rev1")
                    .architecture("llama")
                    .task(io.cortexj.model.TaskCapability.TEXT_GENERATION)
                    .artifact(weights)
                    .artifact(tokenizer)
                    .build();
            java.util.List<io.cortexj.spi.repository.ResolvedArtifact> artifacts =
                    new java.util.ArrayList<io.cortexj.spi.repository.ResolvedArtifact>();
            artifacts.add(new io.cortexj.spi.support.SimpleResolvedArtifact(weights,
                    java.net.URI.create("file:/tmp/definitely-missing.gguf"),
                    java.util.Collections.<String, String>emptyMap()));
            artifacts.add(new io.cortexj.spi.support.SimpleResolvedArtifact(tokenizer,
                    java.net.URI.create("file:/tmp/definitely-missing-tokenizer.json"),
                    java.util.Collections.<String, String>emptyMap()));
            return new io.cortexj.spi.repository.ResolvedModel(descriptor, "rev1",
                    io.cortexj.spi.repository.RepositoryId.of("sidecar-repo"), artifacts);
        }
    }
}
