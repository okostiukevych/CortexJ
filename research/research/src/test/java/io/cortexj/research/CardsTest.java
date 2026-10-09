package io.cortexj.research;

import io.cortexj.meta.ModelIdentity;
import io.cortexj.provenance.ProvenanceManifest;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CardsTest {

    @Test
    void citationRendersAuthorsTitleYearVenue() {
        Cards.Citation citation = new Cards.Citation(
                Arrays.asList("A. Turing", "G. Hopper"), "On Computable Numbers",
                1936, "J. ACM", "doi:10.1145/1");
        assertEquals("A. Turing, G. Hopper. On Computable Numbers (1936). J. ACM. doi:10.1145/1",
                citation.render());
        // title-only citation is valid
        assertEquals("Untitled", new Cards.Citation(null, "Untitled", 0, "", "").render());
    }

    @Test
    void modelCardRendersFacts() {
        Cards.Citation citation = new Cards.Citation(null, "The Model", 2026, "", "");
        Cards.ModelCard card = new Cards.ModelCard(
                ModelIdentity.of("hf:Qwen/Qwen3-8B", "7f62"),
                "text generation", "not for medical advice", "Apache-2.0", citation);
        String rendered = card.render();
        assertTrue(rendered.contains("hf:Qwen/Qwen3-8B@7f62"), rendered);
        assertTrue(rendered.contains("Apache-2.0"), rendered);
        assertTrue(rendered.contains("The Model"), rendered);
    }

    @Test
    void datasetCardRequiresFingerprint() {
        assertThrows(IllegalArgumentException.class,
                () -> new Cards.DatasetCard("", "source", 0, "", null));
        Cards.DatasetCard card = new Cards.DatasetCard("sha256:abc", "local", 100, "CC-BY", null);
        assertTrue(card.render().contains("sha256:abc"));
        assertEquals(100, card.rows());
    }

    @Test
    void experimentBundleCarriesManifestAndChecksums() {
        ProvenanceManifest manifest = ProvenanceManifest.builder()
                .model(ModelIdentity.of("a", "1"))
                .datasetFingerprint("sha256:abc")
                .build();
        Cards.Citation citation = new Cards.Citation(null, "The Paper", 2026, "", "");
        Cards.ExperimentBundle bundle = new Cards.ExperimentBundle(
                manifest,
                new Cards.ModelCard(ModelIdentity.of("a", "1"), "", "", "", citation),
                new Cards.DatasetCard("sha256:abc", "", 0, "", null),
                Map.of("adapter", "sha256:out"));
        assertTrue(bundle.checksumPresent());
        assertEquals("1", bundle.manifest().model().revision());
        assertTrue(bundle.modelCard().render().contains("The Paper"));
    }
}
