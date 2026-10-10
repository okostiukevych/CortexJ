package io.cortexj.data.tribuo;

import io.cortexj.data.ColType;
import io.cortexj.data.Fingerprint;
import io.cortexj.data.Frame;
import org.junit.jupiter.api.Test;
import org.tribuo.MutableDataset;
import org.tribuo.test.MockDataSource;
import org.tribuo.test.MockOutput;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads a Tribuo dataset (mock source from the Tribuo test kit) as a
 * CortexJ frame: features become DOUBLE columns, the output a trailing
 * STRING column.
 */
class TribuoFrameTest {

    @Test
    void readsTribuoDatasetAsFrame() {
        MockDataSource source = new MockDataSource(4);
        MutableDataset<MockOutput> dataset = new MutableDataset<>(source);
        Frame frame = TribuoFrame.read(dataset);
        assertTrue(frame.rowCount() > 0);
        assertEquals(frame.schema().names().get(frame.schema().names().size() - 1), "output");
        assertEquals(ColType.STRING,
                frame.schema().types().get(frame.schema().types().size() - 1));
        for (int c = 0; c < frame.schema().names().size() - 1; c++) {
            assertEquals(ColType.DOUBLE, frame.schema().types().get(c));
        }
        // stable fingerprint across reads
        assertEquals(Fingerprint.ofFrame(frame), Fingerprint.ofFrame(TribuoFrame.read(dataset)));
    }

    @Test
    void outputColumnNameIsConfigurable() {
        MockDataSource source = new MockDataSource(2);
        MutableDataset<MockOutput> dataset = new MutableDataset<>(source);
        Frame frame = TribuoFrame.read(dataset, "label");
        assertTrue(frame.schema().names().contains("label"));
        assertTrue(!frame.schema().names().contains("output"));
    }

    @Test
    void nullDatasetIsRejected() {
        try {
            TribuoFrame.read(null);
            assertTrue(false, "null dataset must fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("dataset"));
        }
    }
}
