package io.cortexj.planner.benchmark;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Locally supplied benchmark hints for the planner. No learning:
 * the file is static, and the scoring derived from it is a deterministic
 * function of the file content alone.
 *
 * <p>Entry format: {@code bench.<runtime>,<family>,<quantization>,<device>,<metric>=<value>}
 * where {@code <runtime>}/{@code <family>}/{@code <quantization>} may be
 * {@code *} (wildcard), device is {@code cpu}/{@code gpu}/{@code npu}/{@code *},
 * and the metric is one of {@code throughputTokensPerSecond} (higher better),
 * {@code latencyMillis} (lower better), {@code memoryGb} (lower better).
 * The family matches as a lowercase substring of the model reference.
 */
public final class BenchmarkHints {

    public static final String FILE_NAME = "benchmarks.properties";
    public static final String SYSPROP = "cortexj.benchmarks.file";

    private static final String PREFIX = "bench.";

    public static final String METRIC_THROUGHPUT = "throughputTokensPerSecond";
    public static final String METRIC_LATENCY = "latencyMillis";
    public static final String METRIC_MEMORY = "memoryGb";

    private final List<Entry> entries;
    private final List<String> warnings;

    private BenchmarkHints(List<Entry> entries, List<String> warnings) {
        this.entries = Collections.unmodifiableList(new ArrayList<Entry>(entries));
        this.warnings = Collections.unmodifiableList(new ArrayList<String>(warnings));
    }

    public static BenchmarkHints empty() {
        return new BenchmarkHints(Collections.<Entry>emptyList(),
                Collections.<String>emptyList());
    }

    public static BenchmarkHints fromFile(Path file) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        }
        return fromProperties(properties);
    }

    public static BenchmarkHints fromProperties(Properties properties) {
        List<Entry> entries = new ArrayList<Entry>();
        List<String> warnings = new ArrayList<String>();
        for (String name : properties.stringPropertyNames()) {
            if (name == null || !name.startsWith(PREFIX)) {
                continue;
            }
            String body = name.substring(PREFIX.length());
            String[] parts = body.split(",");
            if (parts.length != 5) {
                warnings.add("malformed benchmark entry (expected bench.<runtime>,<family>,"
                        + "<quantization>,<device>,<metric>): " + name);
                continue;
            }
            String metric = canonicalMetric(lower(parts[4]));
            if (metric == null) {
                warnings.add("unknown benchmark metric '" + parts[4] + "' in entry " + name);
                continue;
            }
            double value;
            try {
                value = Double.parseDouble(properties.getProperty(name).trim());
            } catch (NumberFormatException invalid) {
                warnings.add("non-numeric benchmark value in entry " + name);
                continue;
            }
            if (value <= 0.0d) {
                warnings.add("benchmark value must be positive in entry " + name);
                continue;
            }
            entries.add(new Entry(lower(parts[0]), lower(parts[1]), lower(parts[2]),
                    lower(parts[3]), metric, value));
        }
        return new BenchmarkHints(entries, warnings);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public List<String> warnings() {
        return warnings;
    }

    /**
     * The best-matching value for a metric, or null when no entry matches.
     * More specific entries (fewer wildcards) win; ties break to the first
     * declared entry so the result is deterministic.
     */
    public Double best(String runtimeId, String modelReference, String quantization,
                       String device, String metric) {
        String runtime = lower(runtimeId);
        String family = lower(modelReference);
        String quant = lower(quantization);
        String dev = lower(device);
        Entry best = null;
        for (Entry entry : entries) {
            if (!entry.metric.equals(metric)) {
                continue;
            }
            if (!entry.matchesRuntime(runtime) || !entry.matchesFamily(family)
                    || !entry.matchesQuantization(quant) || !entry.matchesDevice(dev)) {
                continue;
            }
            if (best == null || entry.specificity() > best.specificity()) {
                best = entry;
            }
        }
        return best == null ? null : Double.valueOf(best.value);
    }

    /**
     * The maximal value of a metric across all entries - the normalization
     * denominator for relative scoring (deterministic: a function of the
     * file alone).
     */
    public double maxOf(String metric) {
        double max = 0.0d;
        for (Entry entry : entries) {
            if (entry.metric.equals(metric) && entry.value > max) {
                max = entry.value;
            }
        }
        return max;
    }

    public double minOf(String metric) {
        double min = Double.POSITIVE_INFINITY;
        for (Entry entry : entries) {
            if (entry.metric.equals(metric) && entry.value < min) {
                min = entry.value;
            }
        }
        return min == Double.POSITIVE_INFINITY ? 0.0d : min;
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String canonicalMetric(String lowercased) {
        if (METRIC_THROUGHPUT.toLowerCase(Locale.ROOT).equals(lowercased)) {
            return METRIC_THROUGHPUT;
        }
        if (METRIC_LATENCY.toLowerCase(Locale.ROOT).equals(lowercased)) {
            return METRIC_LATENCY;
        }
        if (METRIC_MEMORY.toLowerCase(Locale.ROOT).equals(lowercased)) {
            return METRIC_MEMORY;
        }
        return null;
    }

    private static final class Entry {

        private final String runtime;
        private final String family;
        private final String quantization;
        private final String device;
        private final String metric;
        private final double value;

        Entry(String runtime, String family, String quantization,
              String device, String metric, double value) {
            this.runtime = runtime;
            this.family = family;
            this.quantization = quantization;
            this.device = device;
            this.metric = metric;
            this.value = value;
        }

        int specificity() {
            int score = 0;
            if (!"*".equals(runtime)) {
                score++;
            }
            if (!"*".equals(family)) {
                score++;
            }
            if (!"*".equals(quantization)) {
                score++;
            }
            if (!"*".equals(device)) {
                score++;
            }
            return score;
        }

        boolean matchesRuntime(String candidate) {
            return "*".equals(runtime) || runtime.equals(candidate);
        }

        boolean matchesFamily(String modelReference) {
            return "*".equals(family) || modelReference.contains(family);
        }

        boolean matchesQuantization(String candidate) {
            if ("*".equals(quantization)) {
                return true;
            }
            return quantization.equals(candidate);
        }

        boolean matchesDevice(String candidate) {
            return "*".equals(device) || device.equals(candidate);
        }
    }

    static Map<String, String> debugView(BenchmarkHints hints) {
        Map<String, String> view = new LinkedHashMap<String, String>();
        for (Entry entry : hints.entries) {
            view.put(entry.runtime + "," + entry.family + "," + entry.quantization + ","
                    + entry.device + "," + entry.metric, String.valueOf(entry.value));
        }
        return view;
    }
}
