package io.cortexj.data.arrow;

import io.cortexj.data.ColType;
import io.cortexj.data.Column;
import io.cortexj.data.Fingerprint;
import io.cortexj.data.Frame;
import io.cortexj.data.Schema;
import io.cortexj.data.SimpleColumn;
import io.cortexj.data.SimpleFrame;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ArrowFrameTest {

    private static Frame frame() {
        Schema schema = new Schema(
                Arrays.asList("id", "score", "verified", "text"),
                Arrays.asList(ColType.LONG, ColType.DOUBLE, ColType.BOOLEAN, ColType.STRING));
        List<Column<?>> columns = Arrays.<Column<?>>asList(
                new SimpleColumn<Long>("id", Long.class, Arrays.asList(1L, 2L)),
                new SimpleColumn<Double>("score", Double.class, Arrays.asList(4.5, null)),
                new SimpleColumn<Boolean>("verified", Boolean.class, Arrays.asList(true, false)),
                new SimpleColumn<String>("text", String.class, Arrays.asList("great", null)));
        return new SimpleFrame(schema, columns);
    }

    @Test
    void arrowRoundTripKeepsTypesAndNulls() throws Exception {
        Path file = Files.createTempFile("frame", ".arrow");
        try {
            ArrowFrame.write(frame(), file);
            Frame read = ArrowFrame.read(file);
            assertEquals(frame().schema().names(), read.schema().names());
            assertEquals(frame().schema().types(), read.schema().types());
            assertEquals(2, read.rowCount());
            assertEquals(Long.valueOf(1L), read.column("id").get(0));
            assertEquals(Double.valueOf(4.5), (Double) read.column("score").get(0), 1e-9);
            assertNull(read.column("score").get(1));
            assertNull(read.column("text").get(1));
            // the same data yields the same fingerprint through both paths
            assertEquals(Fingerprint.ofFrame(frame()), Fingerprint.ofFrame(read));
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
