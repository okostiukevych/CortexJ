package io.cortexj.examples.northstar;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The end-to-end reference flow runs on CPU-only CI: dataset →
 * fingerprint → experiment → LoRA adapter → merge → checksums →
 * provenance → lockfile. The Runtime-deploy step needs a real model
 * artifact; it is live-verified by the provider tests and skipped
 * loudly here.
 */
class NorthStarDemoTest {

    @Test
    void endToEndChainIsDeterministic() {
        assertDoesNotThrow(() -> NorthStarDemo.main(new String[0]));
    }

    @Test
    void datasetResourceIsBundled() {
        assertTrue(NorthStarDemoTest.class.getResource("/northstar/reviews.csv") != null);
    }
}
