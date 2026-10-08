package io.cortexj.data.csv;

import io.cortexj.data.ColType;
import io.cortexj.data.Fingerprint;
import io.cortexj.data.Frame;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.file.Paths;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CsvFrameTest {

    private static Frame reviews() throws Exception {
        URL resource = CsvFrameTest.class.getResource("/reviews.csv");
        return CsvFrame.read(Paths.get(resource.toURI()));
    }

    @Test
    void readsTypedCsv() throws Exception {
        Frame frame = reviews();
        assertEquals(Arrays.asList("id", "stars", "verified", "text"), frame.schema().names());
        assertEquals(Arrays.asList(ColType.LONG, ColType.LONG, ColType.BOOLEAN, ColType.STRING),
                frame.schema().types());
        assertEquals(3, frame.rowCount());
        assertEquals(Long.valueOf(1L), frame.column("id").get(0));
        assertEquals("loved it, works great", frame.column("text").get(0));
        assertNull(frame.column("verified").get(2));
        assertEquals(2, frame.head(2).rowCount());
    }

    @Test
    void fingerprintIsStableAcrossReads() throws Exception {
        assertEquals(Fingerprint.ofFrame(reviews()), Fingerprint.ofFrame(reviews()));
    }
}
