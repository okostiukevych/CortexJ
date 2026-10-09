package io.cortexj.data.tablesaw;

import io.cortexj.data.ColType;
import io.cortexj.data.Fingerprint;
import io.cortexj.data.Frame;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TablesawFrameTest {

    @Test
    void readsTablesawTableAsCortexJFrame() throws Exception {
        URL resource = TablesawFrameTest.class.getResource("/reviews.csv");
        Frame frame = TablesawFrame.readCsv(Paths.get(resource.toURI()));
        assertEquals(java.util.Arrays.asList("id", "stars", "verified", "text"),
                frame.schema().names());
        assertEquals(java.util.Arrays.asList(ColType.LONG, ColType.LONG, ColType.BOOLEAN,
                ColType.STRING), frame.schema().types());
        assertEquals(3, frame.rowCount());
        assertEquals(Long.valueOf(1L), frame.column("id").get(0));
        assertEquals("loved it, works great", frame.column("text").get(0));
        // same data through the CSV adapter yields the same fingerprint
        Frame csv = io.cortexj.data.csv.CsvFrame.read(Paths.get(resource.toURI()));
        assertEquals(Fingerprint.ofFrame(csv), Fingerprint.ofFrame(frame));
    }
}
