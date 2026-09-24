package io.cortexj.observability.otel.test;

import io.cortexj.observability.PlatformEvent;
import io.cortexj.observability.otel.OtelEventListener;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
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

    private static MetricData metric(InMemoryMetricReader reader, String name) {
        for (MetricData data : reader.collectAllMetrics()) {
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
