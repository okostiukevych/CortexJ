package io.cortexj.data.tribuo;

import io.cortexj.data.ColType;
import io.cortexj.data.Column;
import io.cortexj.data.Frame;
import io.cortexj.data.Schema;
import io.cortexj.data.SimpleColumn;
import io.cortexj.data.SimpleFrame;
import org.tribuo.Dataset;
import org.tribuo.Example;
import org.tribuo.Feature;
import org.tribuo.Output;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Tribuo adapter (ADR-019): reads a Tribuo dataset as a CortexJ frame.
 * Features become DOUBLE columns (sparse features fill with 0.0), the
 * output becomes a trailing STRING column. The library never leaks
 * into data-api signatures.
 */
public final class TribuoFrame {

    private TribuoFrame() {
    }

    public static <T extends Output<T>> Frame read(Dataset<T> dataset) {
        return read(dataset, "output");
    }

    public static <T extends Output<T>> Frame read(Dataset<T> dataset, String outputColumn) {
        if (dataset == null) {
            throw new IllegalArgumentException("dataset must not be null");
        }
        Map<String, Integer> featureIndex = new TreeMap<>();
        List<Example<T>> examples = new ArrayList<>(dataset.size());
        for (Example<T> example : dataset) {
            examples.add(example);
            for (Feature feature : example) {
                featureIndex.putIfAbsent(feature.getName(), featureIndex.size());
            }
        }
        List<String> featureNames = new ArrayList<>(featureIndex.keySet());
        List<String> names = new ArrayList<>(featureNames);
        names.add(outputColumn);
        List<ColType> types = new ArrayList<>();
        for (int i = 0; i < featureNames.size(); i++) {
            types.add(ColType.DOUBLE);
        }
        types.add(ColType.STRING);

        List<List<Double>> featureColumns = new ArrayList<>();
        for (int i = 0; i < featureNames.size(); i++) {
            featureColumns.add(new ArrayList<Double>(examples.size()));
        }
        List<String> outputs = new ArrayList<>(examples.size());
        for (Example<T> example : examples) {
            Map<String, Double> values = new LinkedHashMap<>();
            for (Feature feature : example) {
                values.put(feature.getName(), feature.getValue());
            }
            for (int i = 0; i < featureNames.size(); i++) {
                Double value = values.get(featureNames.get(i));
                // audit 2026-10-10 (SpotBugs BX_UNBOXING_IMMEDIATELY_REBOXED):
                // both branches stay Double - the primitive 0.0 forced an
                // unbox-rebox of every present value
                featureColumns.get(i).add(value != null ? value : Double.valueOf(0.0));
            }
            outputs.add(String.valueOf(example.getOutput()));
        }
        List<Column<?>> columns = new ArrayList<>();
        for (int i = 0; i < featureNames.size(); i++) {
            columns.add(new SimpleColumn<Double>(featureNames.get(i), Double.class,
                    featureColumns.get(i)));
        }
        columns.add(new SimpleColumn<String>(outputColumn, String.class, outputs));
        return new SimpleFrame(new Schema(names, types), columns);
    }
}
