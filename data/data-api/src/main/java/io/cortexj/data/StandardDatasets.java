package io.cortexj.data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Standard synthetic dataset loaders for research and validation:
 * deterministic generators (seeded, no network) producing frames for
 * blobs and linear relationships. The fingerprint contract is identical
 * to file-backed frames.
 */
public final class StandardDatasets {

    private StandardDatasets() {
    }

    /**
     * Gaussian blobs: {@code clusters} centers on a circle, radius
     * {@code spread}, {@code rows} total. Columns: x, y (DOUBLE), label
     * (LONG).
     */
    public static Frame blobs(int rows, int clusters, double spread, long seed) {
        if (rows < clusters || clusters < 1 || spread < 0) {
            throw new IllegalArgumentException("invalid blob parameters");
        }
        Random random = new Random(seed);
        double[][] centers = new double[clusters][2];
        for (int c = 0; c < clusters; c++) {
            double angle = 2 * Math.PI * c / clusters;
            centers[c][0] = 10 * Math.cos(angle);
            centers[c][1] = 10 * Math.sin(angle);
        }
        List<Double> x = new ArrayList<>(rows);
        List<Double> y = new ArrayList<>(rows);
        List<Long> label = new ArrayList<>(rows);
        for (int r = 0; r < rows; r++) {
            int cluster = r % clusters;
            x.add(centers[cluster][0] + random.nextGaussian() * spread);
            y.add(centers[cluster][1] + random.nextGaussian() * spread);
            label.add((long) cluster);
        }
        Schema schema = new Schema(
                Arrays.asList("x", "y", "label"),
                Arrays.asList(ColType.DOUBLE, ColType.DOUBLE, ColType.LONG));
        return new SimpleFrame(schema, Arrays.<Column<?>>asList(
                new SimpleColumn<Double>("x", Double.class, x),
                new SimpleColumn<Double>("y", Double.class, y),
                new SimpleColumn<Long>("label", Long.class, label)));
    }

    /**
     * Linear relationship: y = slope * x + noise, {@code rows} rows.
     * Columns: x, y (DOUBLE).
     */
    public static Frame linear(int rows, double slope, double noise, long seed) {
        if (rows < 1 || noise < 0) {
            throw new IllegalArgumentException("invalid linear parameters");
        }
        Random random = new Random(seed);
        List<Double> x = new ArrayList<>(rows);
        List<Double> y = new ArrayList<>(rows);
        for (int r = 0; r < rows; r++) {
            double input = random.nextDouble() * 10 - 5;
            x.add(input);
            y.add(slope * input + random.nextGaussian() * noise);
        }
        Schema schema = new Schema(
                Arrays.asList("x", "y"),
                Arrays.asList(ColType.DOUBLE, ColType.DOUBLE));
        return new SimpleFrame(schema, Arrays.<Column<?>>asList(
                new SimpleColumn<Double>("x", Double.class, x),
                new SimpleColumn<Double>("y", Double.class, y)));
    }
}
