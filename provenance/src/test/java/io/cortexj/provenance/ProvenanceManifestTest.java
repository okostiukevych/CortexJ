package io.cortexj.provenance;

import io.cortexj.meta.ModelIdentity;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProvenanceManifestTest {

    @Test
    void builderRoundTripAnswersSection148Questions() {
        ProvenanceManifest manifest = ProvenanceManifest.builder()
                .producer("cortexj-train/0.1.0")
                .model(ModelIdentity.of("hf:Qwen/Qwen3-8B", "7f62"))
                .datasetFingerprint("sha256:abc")
                .platformVersion("0.1.0")
                .jdk("Eclipse Temurin 25")
                .osArch("linux-x86_64")
                .backend("jinfer/0.3.1")
                .hardware("cpu-only")
                .parameter("learningRate", "2e-4")
                .seed("shuffle", 42L)
                .parentArtifact("sha256:parent")
                .checksum("adapter.gguf", "sha256:out")
                .createdAtMillis(123L)
                .build();
        assertEquals("1", manifest.schemaVersion());
        assertEquals("cortexj-train/0.1.0", manifest.producer());
        assertEquals("7f62", manifest.model().revision());
        assertEquals("sha256:abc", manifest.datasetFingerprint());
        assertEquals("0.1.0", manifest.platformVersion());
        assertEquals("jinfer/0.3.1", manifest.backend());
        assertEquals("2e-4", manifest.parameters().get("learningRate"));
        assertEquals(Long.valueOf(42L), manifest.seeds().get("shuffle"));
        assertEquals(Collections.singletonList("sha256:parent"), manifest.parentArtifacts());
        assertEquals("sha256:out", manifest.checksums().get("adapter.gguf"));
        assertEquals(123L, manifest.createdAtMillis());
        assertTrue(manifest.checksumPresent());
    }

    @Test
    void missingDataIsExplicitNeverFabricated() {
        ProvenanceManifest manifest = ProvenanceManifest.builder()
                .model(ModelIdentity.of("a", null))
                .build();
        assertEquals("1", manifest.schemaVersion());
        assertEquals("", manifest.datasetFingerprint());
        assertTrue(manifest.parameters().isEmpty());
        assertTrue(manifest.parentArtifacts().isEmpty());
        assertFalse(manifest.checksumPresent());
    }

    @Test
    void nullModelIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ProvenanceManifest.builder().build());
    }

    @Test
    void collectionsAreUnmodifiableAndIgnoreNullEntries() {
        ProvenanceManifest manifest = ProvenanceManifest.builder()
                .model(ModelIdentity.of("hf:test", "rev1"))
                .parameter("k", "v")
                .parameter(null, "ignored")
                .parameter("ignored", null)
                .seed("s", 7L)
                .seed(null, 99L)
                .parentArtifact("parent-1")
                .parentArtifact(null)
                .checksum("weights.gguf", "sha256:123")
                .checksum(null, "sha256:ignored")
                .checksum("ignored.gguf", null)
                .build();

        assertEquals(1, manifest.parameters().size());
        assertEquals(1, manifest.seeds().size());
        assertEquals(1, manifest.parentArtifacts().size());
        assertEquals(1, manifest.checksums().size());
        assertEquals("", manifest.producer());
        assertEquals("", manifest.jdk());
        assertEquals("", manifest.osArch());
        assertEquals("", manifest.backend());
        assertEquals("", manifest.hardware());

        assertThrows(UnsupportedOperationException.class, () -> manifest.parameters().put("x", "y"));
        assertThrows(UnsupportedOperationException.class, () -> manifest.seeds().put("x", 1L));
        assertThrows(UnsupportedOperationException.class, () -> manifest.parentArtifacts().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> manifest.checksums().put("x", "y"));
    }
}
