package io.cortexj.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Standard dataset loaders: deterministic per seed, typed frames,
 * fingerprint-stable.
 */
class StandardDatasetsTest {

    @Test
    void blobsAreTypedAndDeterministic() {
        Frame first = StandardDatasets.blobs(12, 3, 0.5, 42L);
        Frame second = StandardDatasets.blobs(12, 3, 0.5, 42L);
        assertEquals(12, first.rowCount());
        assertEquals(java.util.Arrays.asList("x", "y", "label"), first.schema().names());
        assertEquals(Fingerprint.ofFrame(first), Fingerprint.ofFrame(second));
        // labels cycle through the clusters
        assertEquals(Long.valueOf(0L), first.column("label").get(0));
        assertEquals(Long.valueOf(1L), first.column("label").get(1));
        assertEquals(Long.valueOf(2L), first.column("label").get(2));
        // different seed, different data
        Frame other = StandardDatasets.blobs(12, 3, 0.5, 43L);
        assertTrue(!Fingerprint.ofFrame(first).equals(Fingerprint.ofFrame(other)));
    }

    @Test
    void linearFollowsSlopeWithoutNoise() {
        Frame frame = StandardDatasets.linear(4, 2.0, 0.0, 7L);
        assertEquals(4, frame.rowCount());
        for (int r = 0; r < 4; r++) {
            double x = (Double) frame.column("x").get(r);
            double y = (Double) frame.column("y").get(r);
            assertEquals(2.0 * x, y, 1e-9);
        }
    }

    @Test
    void invalidParametersRejected() {
        assertThrows(IllegalArgumentException.class, () -> StandardDatasets.blobs(2, 3, 0.5, 1L));
        assertThrows(IllegalArgumentException.class, () -> StandardDatasets.linear(0, 1.0, 0.0, 1L));
    }
}
