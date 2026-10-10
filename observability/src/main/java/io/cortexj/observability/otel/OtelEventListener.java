package io.cortexj.observability.otel;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.cortexj.observability.PlatformEvent;
import io.cortexj.observability.PlatformEventListener;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class OtelEventListener implements PlatformEventListener {

    private static final AttributeKey<String> EVENT_TYPE = AttributeKey.stringKey("cortexj.event.type");
    private static final AttributeKey<String> EVENT_LEVEL = AttributeKey.stringKey("cortexj.event.level");
    private static final AttributeKey<String> RUNTIME = AttributeKey.stringKey("cortexj.runtime");
    private static final AttributeKey<String> MODEL = AttributeKey.stringKey("cortexj.model");

    private final Meter meter;
    private final Tracer tracer;
    private final LongCounter eventCounter;
    private final LongCounter inferenceCounter;
    private final LongCounter downloadCounter;
    private final LongCounter downloadFailureCounter;
    private final LongCounter modelLoadedCounter;
    private final LongCounter bytesDownloadedCounter;
    private final io.opentelemetry.api.metrics.DoubleHistogram inferenceHistogram;
    // audit 2026-10-07 (#148): first-token latency gets its own histogram -
    // mixing it into the duration histogram made p95-first-token unbuildable
    private final io.opentelemetry.api.metrics.DoubleHistogram firstTokenHistogram;
    // audit 2026-10-07 (#148): a per-key span *stack* instead of a single
    // slot - concurrent loads of the same model:artifact used to collapse
    // onto one span (losers discarded unclosed pre-#101, then attributed to
    // the winner). LIFO end matches the common nested case; every started
    // span is ended exactly once either way.
    // audit 2026-10-10: frames carry start times so orphaned spans (a load
    // that dies without any terminal event) are swept instead of leaking
    // one map entry per distinct model:artifact forever.
    private final Map<String, java.util.Deque<SpanFrame>> spansByModel =
            new ConcurrentHashMap<String, java.util.Deque<SpanFrame>>();
    private static final int MAX_SPAN_KEYS = 1024;
    private static final long STALE_SPAN_MILLIS = 60L * 60L * 1000L;

    private static final class SpanFrame {
        final Span span;
        final long startedMillis;

        SpanFrame(Span span, long startedMillis) {
            this.span = span;
            this.startedMillis = startedMillis;
        }
    }

    public OtelEventListener() {
        this(io.opentelemetry.api.GlobalOpenTelemetry.get());
    }

    public OtelEventListener(OpenTelemetry telemetry) {
        this.meter = telemetry.getMeter("io.cortexj");
        this.tracer = telemetry.getTracer("io.cortexj");
        this.eventCounter = meter.counterBuilder("cortexj.platform.events")
                .setDescription("Platform lifecycle and orchestration events")
                .build();
        this.inferenceCounter = meter.counterBuilder("cortexj.inference.completed")
                .setDescription("Completed inference calls")
                .build();
        this.downloadCounter = meter.counterBuilder("cortexj.artifact.downloads")
                .setDescription("Completed artifact downloads")
                .build();
        this.downloadFailureCounter = meter.counterBuilder("cortexj.artifact.download.failures")
                .setDescription("Failed artifact downloads")
                .build();
        this.modelLoadedCounter = meter.counterBuilder("cortexj.model.loaded")
                .setDescription("Models loaded into runtimes")
                .build();
        this.bytesDownloadedCounter = meter.counterBuilder("cortexj.artifact.downloaded.bytes")
                .setDescription("Bytes downloaded into the cache")
                .build();
        this.inferenceHistogram = meter.histogramBuilder("cortexj.operation.duration")
                .setDescription("Duration of timed platform operations (planning, downloads, inference)")
                .setUnit("ms")
                .build();
        this.firstTokenHistogram = meter.histogramBuilder("cortexj.inference.first_token")
                .setDescription("Time to first token for streaming inference")
                .setUnit("ms")
                .build();
    }

    @Override
    public void onEvent(PlatformEvent event) {
        Attributes attributes = attributes(event);
        eventCounter.add(1, attributes);
        // audit 2026-10-08: each metric parses independently - one malformed
        // value must not drop the event counter, the sibling histogram, the
        // type-switched counters below, or the span lifecycle
        recordLong(event.attributes().get("durationMillis"), inferenceHistogram, attributes);
        recordLong(event.attributes().get("firstTokenMillis"), firstTokenHistogram, attributes);
        switch (event.type()) {
            case MODEL_RESOLUTION_STARTED:
                startSpan(event, "cortexj.model.resolution");
                break;
            case MODEL_RESOLUTION_COMPLETED:
                endSpan(event);
                break;
            case DOWNLOAD_STARTED:
                startSpan(event, "cortexj.artifact.download");
                break;
            case MODEL_LOADED:
                modelLoadedCounter.add(1, attributes);
                // audit #101: a successful load always ends the resolution span;
                // MODEL_RESOLUTION_COMPLETED only fires on the trust-warn path
                endSpan(event);
                break;
            case DOWNLOAD_COMPLETED:
                downloadCounter.add(1, attributes);
                recordBytes(event);
                endSpan(event);
                break;
            case DOWNLOAD_FAILED:
                downloadFailureCounter.add(1, attributes);
                endSpan(event);
                break;
            case FALLBACK_APPLIED:
                endSpan(event);
                break;
            case INFERENCE_COMPLETED:
                inferenceCounter.add(1, attributes);
                break;
            default:
                break;
        }
    }

    private void startSpan(PlatformEvent event, String name) {
        // audit #101: the span is created inside computeIfAbsent so a losing
        // concurrent load never starts a span it would then discard unclosed
        if (spansByModel.size() >= MAX_SPAN_KEYS) {
            sweepStaleSpans();
        }
        java.util.Deque<SpanFrame> stack = spansByModel.get(spanKey(event));
        if (stack == null) {
            java.util.Deque<SpanFrame> fresh = new java.util.concurrent.ConcurrentLinkedDeque<SpanFrame>();
            java.util.Deque<SpanFrame> raced = spansByModel.putIfAbsent(spanKey(event), fresh);
            stack = raced == null ? fresh : raced;
        }
        stack.push(new SpanFrame(tracer.spanBuilder(name)
                .setAttribute(MODEL, event.attributes().getOrDefault("model", "unknown"))
                .startSpan(), System.currentTimeMillis()));
    }

    private static void recordLong(String raw,
                                   io.opentelemetry.api.metrics.DoubleHistogram histogram,
                                   Attributes attributes) {
        if (raw == null) {
            return;
        }
        try {
            histogram.record(Double.parseDouble(raw), attributes);
        } catch (NumberFormatException ignored) {
            // malformed values never fail the event pipeline
        }
    }

    private void recordBytes(PlatformEvent event) {
        String bytes = event.attributes().get("bytes");
        if (bytes != null) {
            try {
                // audit 2026-10-10: the byte count is the measurement, not
                // an attribute - keying series by exact size (plus model and
                // artifact, see attributes()) explodes metric cardinality
                bytesDownloadedCounter.add(Long.parseLong(bytes), runtimeAttributes(event));
            } catch (NumberFormatException ignored) {
                return;
            }
        }
    }

    private void endSpan(PlatformEvent event) {
        java.util.Deque<SpanFrame> stack = spansByModel.get(spanKey(event));
        if (stack == null) {
            return;
        }
        SpanFrame frame = stack.poll();
        if (stack.isEmpty()) {
            spansByModel.remove(spanKey(event), stack);
        }
        if (frame != null) {
            endFrame(frame, event);
        }
    }

    private static void endFrame(SpanFrame frame, PlatformEvent event) {
        if (frame == null || frame.span == null) {
            return;
        }
        if (event != null && event.level() == PlatformEvent.Level.ERROR) {
            frame.span.setAttribute("cortexj.error", event.message());
            frame.span.setStatus(StatusCode.ERROR, event.message());
        }
        frame.span.end();
    }

    private void sweepStaleSpans() {
        long cutoff = System.currentTimeMillis() - STALE_SPAN_MILLIS;
        for (Map.Entry<String, java.util.Deque<SpanFrame>> entry : spansByModel.entrySet()) {
            java.util.Deque<SpanFrame> stack = entry.getValue();
            if (stack == null || stack.isEmpty()) {
                spansByModel.remove(entry.getKey(), stack);
                continue;
            }
            boolean allStale = true;
            for (SpanFrame frame : stack) {
                if (frame != null && frame.startedMillis >= cutoff) {
                    allStale = false;
                    break;
                }
            }
            if (allStale && spansByModel.remove(entry.getKey(), stack)) {
                SpanFrame frame;
                while ((frame = stack.poll()) != null) {
                    endFrame(frame, null);
                }
            }
        }
    }

    private static String spanKey(PlatformEvent event) {
        String model = event.attributes().getOrDefault("model", "");
        String artifact = event.attributes().getOrDefault("artifact", "");
        return model + ":" + artifact;
    }

    private static Attributes attributes(PlatformEvent event) {
        // audit 2026-10-10: metric attributes stay low-cardinality
        // (type/level/runtime). Model, artifact and exact byte counts used
        // to ride every counter and histogram point, creating one series
        // per distinct value - they remain on spans, where cardinality is
        // expected, and byte counts travel as measurements.
        AttributesBuilder builder = Attributes.builder()
                .put(EVENT_TYPE, event.type().name())
                .put(EVENT_LEVEL, event.level().name());
        String runtime = event.attributes().get("runtime");
        if (runtime != null) {
            builder.put(RUNTIME, runtime);
        }
        return builder.build();
    }

    private static Attributes runtimeAttributes(PlatformEvent event) {
        AttributesBuilder builder = Attributes.builder();
        String runtime = event.attributes().get("runtime");
        if (runtime != null) {
            builder.put(RUNTIME, runtime);
        }
        return builder.build();
    }
}
