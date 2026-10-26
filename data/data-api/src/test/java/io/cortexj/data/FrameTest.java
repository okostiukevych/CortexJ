package io.cortexj.data;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameTest {

    private static Frame frame() {
        Schema schema = new Schema(
                Arrays.asList("id", "text"),
                Arrays.asList(ColType.LONG, ColType.STRING));
        List<Column<?>> columns = Arrays.<Column<?>>asList(
                new SimpleColumn<Long>("id", Long.class, Arrays.asList(1L, 2L, 3L)),
                new SimpleColumn<String>("text", String.class, Arrays.asList("a", null, "c")));
        return new SimpleFrame(schema, columns);
    }

    @Test
    void headSelectAndRows() {
        Frame frame = frame();
        assertEquals(3, frame.rowCount());
        assertEquals(2, frame.head(2).rowCount());
        Frame selected = frame.select("text");
        assertEquals(Arrays.asList("text"), selected.schema().names());
        List<List<String>> rows = frame.rows();
        assertEquals(Arrays.asList("1", "a"), rows.get(0));
        assertEquals(Arrays.asList(null, null).size(), rows.get(1).size());
    }

    @Test
    void raggedColumnsAreRejected() {
        Schema schema = new Schema(
                Arrays.asList("a", "b"),
                Arrays.asList(ColType.LONG, ColType.LONG));
        List<Column<?>> columns = Arrays.<Column<?>>asList(
                new SimpleColumn<Long>("a", Long.class, Arrays.asList(1L)),
                new SimpleColumn<Long>("b", Long.class, Arrays.asList(1L, 2L)));
        assertThrows(IllegalArgumentException.class, () -> new SimpleFrame(schema, columns));
    }

    @Test
    void fingerprintIsStableAndSensitive() {
        assertEquals(Fingerprint.ofFrame(frame()), Fingerprint.ofFrame(frame()));
        Frame other = new SimpleFrame(frame().schema(), Arrays.<Column<?>>asList(
                new SimpleColumn<Long>("id", Long.class, Arrays.asList(9L, 2L, 3L)),
                new SimpleColumn<String>("text", String.class, Arrays.asList("a", null, "c"))));
        assertTrue(!Fingerprint.ofFrame(frame()).equals(Fingerprint.ofFrame(other)));

        // Null cell vs literal "<null>" string must produce distinct fingerprints
        Frame literalNull = new SimpleFrame(frame().schema(), Arrays.<Column<?>>asList(
                new SimpleColumn<Long>("id", Long.class, Arrays.asList(1L, 2L, 3L)),
                new SimpleColumn<String>("text", String.class, Arrays.asList("a", "<null>", "c"))));
        assertTrue(!Fingerprint.ofFrame(frame()).equals(Fingerprint.ofFrame(literalNull)));
    }

    @Test
    void splitIsDeterministicAndLossless() {
        FrameDataset dataset = new FrameDataset(frame());
        Split<Dataset<List<String>>, Dataset<List<String>>> first = dataset.split(0.6, 42L);
        Split<Dataset<List<String>>, Dataset<List<String>>> second = dataset.split(0.6, 42L);
        assertEquals(first.train().fingerprint(), second.train().fingerprint());
        assertEquals(3L, first.train().count() + first.test().count());
    }

    @Test
    void batchesPageInOrder() {
        List<Dataset<List<String>>> pages = new FrameDataset(frame()).batches(2);
        assertEquals(2, pages.size());
        assertEquals(2L, pages.get(0).count());
        assertEquals(1L, pages.get(1).count());
    }
}
