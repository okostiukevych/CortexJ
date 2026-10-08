package io.cortexj.data;

import java.util.List;

public interface Dataset<T> extends Iterable<T> {

    long count();

    String fingerprint();

    List<Dataset<T>> batches(int batchSize);

    Split<Dataset<T>, Dataset<T>> split(double trainFraction, long seed);
}
