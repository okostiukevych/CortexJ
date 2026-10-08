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
    private static final AttributeKey<String> ARTIFACT = AttributeKey.stringKey("cortexj.artifact");
    private static final AttributeKey<String> BYTES = AttributeKey.stringKey("cortexj.bytes");

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
    private final Map<String, java.util.Deque<Span>> spansByModel =
            new ConcurrentHashMap<String, java.util.Deque<Span>>();

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
        java.util.Deque<Span> stack = spansByModel.get(spanKey(event));
        if (stack == null) {
            java.util.Deque<Span> fresh = new java.util.concurrent.ConcurrentLinkedDeque<Span>();
            java.util.Deque<Span> raced = spansByModel.putIfAbsent(spanKey(event), fresh);
            stack = raced == null ? fresh : raced;
        }
        stack.push(tracer.spanBuilder(name)
                .setAttribute(MODEL, event.attributes().getOrDefault("model", "unknown"))
                .startSpan());
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
                bytesDownloadedCounter.add(Long.parseLong(bytes), attributes(event));
            } catch (NumberFormatException ignored) {
                return;
            }
        }
    }

    private void endSpan(PlatformEvent event) {
        java.util.Deque<Span> stack = spansByModel.get(spanKey(event));
        if (stack == null) {
            return;
        }
        Span span = stack.poll();
        if (stack.isEmpty()) {
            spansByModel.remove(spanKey(event), stack);
        }
        if (span != null) {
            if (event.level() == PlatformEvent.Level.ERROR) {
                span.setAttribute("cortexj.error", event.message());
                span.setStatus(StatusCode.ERROR, event.message());
            }
            span.end();
        }
    }

    private static String spanKey(PlatformEvent event) {
        String model = event.attributes().getOrDefault("model", "");
        String artifact = event.attributes().getOrDefault("artifact", "");
        return model + ":" + artifact;
    }

    private static Attributes attributes(PlatformEvent event) {
        AttributesBuilder builder = Attributes.builder()
                .put(EVENT_TYPE, event.type().name())
                .put(EVENT_LEVEL, event.level().name());
        Map<String, String> source = event.attributes();
        String runtime = source.get("runtime");
        if (runtime != null) {
            builder.put(RUNTIME, runtime);
        }
        String model = source.get("model");
        if (model != null) {
            builder.put(MODEL, model);
        }
        String artifact = source.get("artifact");
        if (artifact != null) {
            builder.put(ARTIFACT, artifact);
        }
        String bytes = source.get("bytes");
        if (bytes != null) {
            builder.put(BYTES, bytes);
        }
        return builder.build();
    }
}
