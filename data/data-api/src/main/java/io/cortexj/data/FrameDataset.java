package io.cortexj.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * A {@link Frame} viewed as a row dataset (rows are string-cell lists,
 * null for missing values). Split shuffles row indices with the given
 * seed, so the same frame and seed always split identically.
 */
public final class FrameDataset implements Dataset<List<String>> {

    private final Frame frame;

    public FrameDataset(Frame frame) {
        if (frame == null) {
            throw new IllegalArgumentException("frame must not be null");
        }
        this.frame = frame;
    }

    public Frame frame() {
        return frame;
    }

    @Override
    public Iterator<List<String>> iterator() {
        return frame.rows().iterator();
    }

    @Override
    public long count() {
        return frame.rowCount();
    }

    @Override
    public String fingerprint() {
        return Fingerprint.ofFrame(frame);
    }

    @Override
    public List<Dataset<List<String>>> batches(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batch size must be positive");
        }
        List<List<String>> rows = frame.rows();
        List<Dataset<List<String>>> pages = new ArrayList<Dataset<List<String>>>();
        for (int from = 0; from < rows.size(); from += batchSize) {
            int to = Math.min(from + batchSize, rows.size());
            pages.add(new FrameDataset(new SimpleFrame(frame.schema(), columnsFor(rows, from, to))));
        }
        return pages;
    }

    @Override
    public Split<Dataset<List<String>>, Dataset<List<String>>> split(double trainFraction, long seed) {
        if (trainFraction < 0 || trainFraction > 1) {
            throw new IllegalArgumentException("train fraction must sit in [0, 1]");
        }
        List<List<String>> rows = new ArrayList<List<String>>(frame.rows());
        Collections.shuffle(rows, new Random(seed));
        int cut = (int) Math.round(rows.size() * trainFraction);
        return new Split<Dataset<List<String>>, Dataset<List<String>>>(
                new FrameDataset(frameFor(rows.subList(0, cut))),
                new FrameDataset(frameFor(rows.subList(cut, rows.size()))));
    }

    private SimpleFrame frameFor(List<List<String>> rows) {
        return new SimpleFrame(frame.schema(), columnsFor(rows, 0, rows.size()));
    }

    private List<Column<?>> columnsFor(List<List<String>> rows, int from, int to) {
        List<Column<?>> columns = new ArrayList<Column<?>>(frame.schema().size());
        for (int c = 0; c < frame.schema().size(); c++) {
            columns.add(columnFor(c, rows, from, to));
        }
        return columns;
    }

    private Column<?> columnFor(int index, List<List<String>> rows, int from, int to) {
        String name = frame.schema().names().get(index);
        switch (frame.schema().types().get(index)) {
            case LONG: {
                List<Long> values = new ArrayList<Long>(to - from);
                for (int r = from; r < to; r++) {
                    String cell = rows.get(r).get(index);
                    values.add(cell == null ? null : Long.valueOf(cell));
                }
                return new SimpleColumn<Long>(name, Long.class, values);
            }
            case DOUBLE: {
                List<Double> values = new ArrayList<Double>(to - from);
                for (int r = from; r < to; r++) {
                    String cell = rows.get(r).get(index);
                    values.add(cell == null ? null : Double.valueOf(cell));
                }
                return new SimpleColumn<Double>(name, Double.class, values);
            }
            case BOOLEAN: {
                List<Boolean> values = new ArrayList<Boolean>(to - from);
                for (int r = from; r < to; r++) {
                    String cell = rows.get(r).get(index);
                    values.add(cell == null ? null : Boolean.valueOf(cell));
                }
                return new SimpleColumn<Boolean>(name, Boolean.class, values);
            }
            case STRING:
            default: {
                List<String> values = new ArrayList<String>(to - from);
                for (int r = from; r < to; r++) {
                    values.add(rows.get(r).get(index));
                }
                return new SimpleColumn<String>(name, String.class, values);
            }
        }
    }
}
