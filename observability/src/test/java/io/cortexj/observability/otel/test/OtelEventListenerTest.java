package io.cortexj.observability.otel.test;

import io.cortexj.observability.PlatformEvent;
import io.cortexj.observability.otel.OtelEventListener;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OtelEventListenerTest {

    private static PlatformEvent event(PlatformEvent.Type type, PlatformEvent.Level level, String message,
                                       String model, String artifact, String bytes) {
        Map<String, String> attributes = new LinkedHashMap<String, String>();
        if (model != null) {
            attributes.put("model", model);
        }
        if (artifact != null) {
            attributes.put("artifact", artifact);
        }
        if (bytes != null) {
            attributes.put("bytes", bytes);
        }
        return new PlatformEvent(type, level, message, attributes, 0L);
    }

    @Test
    void eventsAreCountedPerTypeAndLevel() {
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        SdkMeterProvider provider = SdkMeterProvider.builder()
                .registerMetricReader(reader)
                .build();
        OtelEventListener listener = new OtelEventListener(
                OpenTelemetrySdk.builder().setMeterProvider(provider).build());

        listener.onEvent(event(PlatformEvent.Type.PLAN_CREATED, PlatformEvent.Level.INFO, "plan ready", null, null, null));
        listener.onEvent(event(PlatformEvent.Type.PLAN_CREATED, PlatformEvent.Level.INFO, "plan ready", null, null, null));
        listener.onEvent(event(PlatformEvent.Type.INFERENCE_COMPLETED, PlatformEvent.Level.INFO, "done",
                "qwen3", null, null));
        listener.onEvent(event(PlatformEvent.Type.DOWNLOAD_COMPLETED, PlatformEvent.Level.INFO, "downloaded",
                null, "model.gguf", "1048576"));
        listener.onEvent(event(PlatformEvent.Type.DOWNLOAD_FAILED, PlatformEvent.Level.ERROR, "failed",
                null, "model.gguf", null));

        MetricData events = metric(reader, "cortexj.platform.events");
        assertEquals(5L, events.getLongSumData().getPoints().size() > 0 ? sum(events) : 0);

        MetricData inference = metric(reader, "cortexj.inference.completed");
        assertEquals(1L, sum(inference));

        MetricData downloads = metric(reader, "cortexj.artifact.downloads");
        assertEquals(1L, sum(downloads));

        MetricData failures = metric(reader, "cortexj.artifact.download.failures");
        assertEquals(1L, sum(failures));

        MetricData bytes = metric(reader, "cortexj.artifact.downloaded.bytes");
        assertEquals(1048576L, sum(bytes));
    }

    @Test
    void emptyAttributesAreTolerated() {
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        SdkMeterProvider provider = SdkMeterProvider.builder()
                .registerMetricReader(reader)
                .build();
        OtelEventListener listener = new OtelEventListener(
                OpenTelemetrySdk.builder().setMeterProvider(provider).build());
        listener.onEvent(new PlatformEvent(PlatformEvent.Type.PLATFORM_STARTED, PlatformEvent.Level.INFO,
                "started", null, 0L));
        assertEquals(1L, sum(metric(reader, "cortexj.platform.events")));
    }

    @Test
    void malformedBytesAttributeIsIgnored() {
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        SdkMeterProvider provider = SdkMeterProvider.builder()
                .registerMetricReader(reader)
                .build();
        OtelEventListener listener = new OtelEventListener(
                OpenTelemetrySdk.builder().setMeterProvider(provider).build());
        listener.onEvent(event(PlatformEvent.Type.DOWNLOAD_COMPLETED, PlatformEvent.Level.INFO, "downloaded",
                null, "x.gguf", "not-a-number"));
        MetricData bytesMetric = metric(reader, "cortexj.artifact.downloaded.bytes");
        assertEquals(0L, bytesMetric == null ? 0L : sum(bytesMetric));
        assertEquals(1L, sum(metric(reader, "cortexj.artifact.downloads")));
    }

    @Test
    void completedLoadEndsAllSpans() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OtelEventListener listener = new OtelEventListener(
                OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build());

        listener.onEvent(event(PlatformEvent.Type.MODEL_RESOLUTION_STARTED, PlatformEvent.Level.INFO,
                "resolving", "qwen3", null, null));
        listener.onEvent(event(PlatformEvent.Type.MODEL_RESOLUTION_COMPLETED, PlatformEvent.Level.INFO,
                "resolved", "qwen3", null, null));
        listener.onEvent(event(PlatformEvent.Type.DOWNLOAD_STARTED, PlatformEvent.Level.INFO,
                "downloading", "qwen3", "qwen3.gguf", null));
        listener.onEvent(event(PlatformEvent.Type.DOWNLOAD_COMPLETED, PlatformEvent.Level.INFO,
                "downloaded", "qwen3", "qwen3.gguf", "1048576"));

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(2, spans.size());
        assertEquals("cortexj.model.resolution", spans.get(0).getName());
        assertEquals("cortexj.artifact.download", spans.get(1).getName());
        AttributeKey<String> modelKey = AttributeKey.stringKey("cortexj.model");
        for (SpanData span : spans) {
            assertTrue(span.hasEnded());
        }
        assertEquals("qwen3", spans.get(0).getAttributes().get(modelKey));
        assertEquals("qwen3", spans.get(1).getAttributes().get(modelKey));
    }

    @Test
    void failedLoadEndsSpanWithErrorStatus() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OtelEventListener listener = new OtelEventListener(
                OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build());

        listener.onEvent(event(PlatformEvent.Type.DOWNLOAD_STARTED, PlatformEvent.Level.INFO,
                "downloading", "qwen3", "qwen3.gguf", null));
        listener.onEvent(event(PlatformEvent.Type.DOWNLOAD_FAILED, PlatformEvent.Level.ERROR,
                "boom", "qwen3", "qwen3.gguf", null));

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(1, spans.size());
        SpanData span = spans.get(0);
        assertEquals("cortexj.artifact.download", span.getName());
        assertTrue(span.hasEnded());
        assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
        assertEquals("boom", span.getAttributes().get(AttributeKey.stringKey("cortexj.error")));
    }

    @Test
    void fallbackAppliedEndsOpenResolutionSpan() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OtelEventListener listener = new OtelEventListener(
                OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build());

        listener.onEvent(event(PlatformEvent.Type.MODEL_RESOLUTION_STARTED, PlatformEvent.Level.INFO,
                "resolving", "qwen3", null, null));
        listener.onEvent(event(PlatformEvent.Type.FALLBACK_APPLIED, PlatformEvent.Level.WARNING,
                "load failed; trying fallback", "qwen3", null, null));

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(1, spans.size());
        SpanData span = spans.get(0);
        assertEquals("cortexj.model.resolution", span.getName());
        assertTrue(span.hasEnded());
        assertEquals(StatusCode.UNSET, span.getStatus().getStatusCode());
    }

    @Test
    void modelLoadedCounterIsReusedAcrossEvents() {
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        SdkMeterProvider provider = SdkMeterProvider.builder()
                .registerMetricReader(reader)
                .build();
        OtelEventListener listener = new OtelEventListener(
                OpenTelemetrySdk.builder().setMeterProvider(provider).build());

        for (int i = 0; i < 3; i++) {
            listener.onEvent(event(PlatformEvent.Type.MODEL_LOADED, PlatformEvent.Level.INFO,
                    "loaded", "qwen3", null, null));
        }
        assertEquals(3L, sum(metric(reader, "cortexj.model.loaded")));
        int streamsAfterFirstBatch = reader.collectAllMetrics().size();

        for (int i = 0; i < 3; i++) {
            listener.onEvent(event(PlatformEvent.Type.MODEL_LOADED, PlatformEvent.Level.INFO,
                    "loaded", "qwen3", null, null));
        }
        assertEquals(6L, sum(metric(reader, "cortexj.model.loaded")));
        assertEquals(streamsAfterFirstBatch, reader.collectAllMetrics().size());
    }

    @Test
    void modelLoadedEndsTheResolutionSpan() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OtelEventListener listener = new OtelEventListener(
                OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build());

        // audit #101: a successful load ends with MODEL_LOADED (not
        // MODEL_RESOLUTION_COMPLETED, which only fires on the trust-warn path)
        listener.onEvent(event(PlatformEvent.Type.MODEL_RESOLUTION_STARTED, PlatformEvent.Level.INFO,
                "resolving", "qwen3", "qwen3.gguf", null));
        listener.onEvent(event(PlatformEvent.Type.MODEL_LOADED, PlatformEvent.Level.INFO,
                "loaded", "qwen3", "qwen3.gguf", null));

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(1, spans.size(),
                "the resolution span must be ended by MODEL_LOADED");
        assertTrue(spans.get(0).hasEnded());
    }

    @Test
    void concurrentLoadsOfDistinctModelsGetDistinctEndedSpans() throws Exception {
        final InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        final OtelEventListener listener = new OtelEventListener(
                OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build());

        // audit #101: span keys include model+artifact, and a losing
        // computeIfAbsent must never start a span it would discard unclosed
        final java.util.concurrent.CountDownLatch started =
                new java.util.concurrent.CountDownLatch(2);
        final java.util.concurrent.CountDownLatch finished =
                new java.util.concurrent.CountDownLatch(2);
        for (int i = 0; i < 2; i++) {
            final int slot = i;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        String model = "model-" + slot;
                        listener.onEvent(event(PlatformEvent.Type.MODEL_RESOLUTION_STARTED,
                                PlatformEvent.Level.INFO, "resolving", model, model + ".gguf", null));
                        listener.onEvent(event(PlatformEvent.Type.MODEL_LOADED,
                                PlatformEvent.Level.INFO, "loaded", model, model + ".gguf", null));
                    } finally {
                        started.countDown();
                        finished.countDown();
                    }
                }
            }).start();
        }
        assertTrue(started.await(10, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(finished.await(10, java.util.concurrent.TimeUnit.SECONDS));

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(2, spans.size(),
                "both concurrent loads must produce their own ended spans");
        for (SpanData span : spans) {
            assertTrue(span.hasEnded(), "no span may leak unclosed");
        }
    }

    @Test
    void malformedMetricValueDoesNotDropTheEvent() {
        // audit 2026-10-08 (N2): one bad value used to return before the
        // counters and span handling below
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        SdkMeterProvider provider = SdkMeterProvider.builder()
                .registerMetricReader(reader)
                .build();
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider tracers = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OtelEventListener listener = new OtelEventListener(OpenTelemetrySdk.builder()
                .setMeterProvider(provider).setTracerProvider(tracers).build());

        Map<String, String> attributes = new LinkedHashMap<String, String>();
        attributes.put("model", "m");
        attributes.put("artifact", "a.gguf");
        attributes.put("durationMillis", "12");
        attributes.put("firstTokenMillis", "null");
        listener.onEvent(new PlatformEvent(PlatformEvent.Type.INFERENCE_COMPLETED,
                PlatformEvent.Level.INFO, "done", attributes, 0L));

        MetricData inference = metric(reader, "cortexj.inference.completed");
        assertTrue(inference != null && sum(inference) == 1L,
                "INFERENCE_COMPLETED must be counted despite the bad first-token value");
        MetricData duration = metric(reader, "cortexj.operation.duration");
        assertTrue(duration != null, "the valid duration histogram must still record");
    }

    @Test
    void firstTokenLatencyHasItsOwnHistogram() {
        // audit 2026-10-07 (#148): p95-first-token must be buildable
        // separately from operation durations
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        SdkMeterProvider provider = SdkMeterProvider.builder()
                .registerMetricReader(reader)
                .build();
        OtelEventListener listener = new OtelEventListener(OpenTelemetrySdk.builder()
                .setMeterProvider(provider).build());

        Map<String, String> attributes = new LinkedHashMap<String, String>();
        attributes.put("model", "m");
        attributes.put("firstTokenMillis", "42");
        listener.onEvent(new PlatformEvent(PlatformEvent.Type.INFERENCE_FIRST_TOKEN,
                PlatformEvent.Level.INFO, "first token", attributes, 0L));

        MetricData firstToken = metric(reader, "cortexj.inference.first_token");
        assertTrue(firstToken != null, "the first-token histogram must exist");
        long count = 0L;
        for (io.opentelemetry.sdk.metrics.data.HistogramPointData point
                : firstToken.getHistogramData().getPoints()) {
            count += point.getCount();
        }
        assertEquals(1L, count, "exactly one first-token measurement must be recorded");
    }

    private static MetricData metric(InMemoryMetricReader reader, String name) {        for (MetricData data : reader.collectAllMetrics()) {
            if (name.equals(data.getName())) {
                return data;
            }
        }
        return null;
    }

    private static long sum(MetricData data) {
        long total = 0L;
        for (io.opentelemetry.sdk.metrics.data.LongPointData point : data.getLongSumData().getPoints()) {
            total += point.getValue();
        }
        return total;
    }
}
