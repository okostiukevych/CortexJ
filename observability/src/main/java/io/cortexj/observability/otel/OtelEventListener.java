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
    private final Map<String, Span> spansByModel = new ConcurrentHashMap<String, Span>();

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
    }

    @Override
    public void onEvent(PlatformEvent event) {
        Attributes attributes = attributes(event);
        eventCounter.add(1, attributes);
        String durationMillis = event.attributes().get("durationMillis");
        String firstTokenMillis = event.attributes().get("firstTokenMillis");
        try {
            if (durationMillis != null) {
                inferenceHistogram.record(Double.parseDouble(durationMillis), attributes);
            }
            if (firstTokenMillis != null) {
                inferenceHistogram.record(Double.parseDouble(firstTokenMillis), attributes);
            }
        } catch (NumberFormatException ignored) {
            return;
        }
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
        spansByModel.putIfAbsent(spanKey(event), tracer.spanBuilder(name)
                .setAttribute(MODEL, event.attributes().getOrDefault("model", "unknown"))
                .startSpan());
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
        Span span = spansByModel.remove(spanKey(event));
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
