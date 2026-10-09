package io.cortexj.benchmarks;

import io.cortexj.data.ColType;
import io.cortexj.data.Column;
import io.cortexj.data.Fingerprint;
import io.cortexj.data.Frame;
import io.cortexj.data.Schema;
import io.cortexj.data.SimpleColumn;
import io.cortexj.data.SimpleFrame;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Data-fingerprint benchmark (perf-tagged; run: mvn test -Pperf -pl
 * benchmarks). Measures the identity layer, not the file IO.
 */
@Tag("perf")
class FingerprintBenchmarkTest {

    @Test
    void fingerprintThroughputOnLargeFrame() {
        int rows = 10_000;
        Schema schema = new Schema(Arrays.asList("id", "text"),
                Arrays.asList(ColType.LONG, ColType.STRING));
        List<Column<?>> columns = Arrays.<Column<?>>asList(
                new SimpleColumn<Long>("id", Long.class, ids(rows)),
                new SimpleColumn<String>("text", String.class, texts(rows)));
        Frame frame = new SimpleFrame(schema, columns);
        long start = System.nanoTime();
        String fingerprint = Fingerprint.ofFrame(frame);
        long nanos = System.nanoTime() - start;
        System.out.printf("fingerprint %d rows: %.1f ms (%s)%n", rows, nanos / 1e6, fingerprint);
        assertTrue(nanos > 0);
    }

    private static List<Long> ids(int rows) {
        List<Long> values = new ArrayList<Long>(rows);
        for (long i = 0; i < rows; i++) {
            values.add(i);
        }
        return values;
    }

    private static List<String> texts(int rows) {
        List<String> values = new ArrayList<String>(rows);
        Random random = new Random(42L);
        String alphabet = "abcdefghij ";
        for (int i = 0; i < rows; i++) {
            StringBuilder text = new StringBuilder();
            for (int c = 0; c < 32; c++) {
                text.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            values.add(text.toString());
        }
        return values;
    }
}
