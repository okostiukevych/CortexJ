package io.cortexj.runtime.remote;

import io.cortexj.ErrorCode;
import io.cortexj.error.RuntimeCompatibilityException;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.TaskCapability;
import io.cortexj.runtime.DeviceType;
import io.cortexj.runtime.RuntimeDescriptor;
import io.cortexj.runtime.RuntimeId;
import io.cortexj.spi.runtime.ModelRuntime;
import io.cortexj.spi.runtime.RuntimeEvaluationRequest;
import io.cortexj.spi.runtime.RuntimeOpenRequest;
import io.cortexj.spi.runtime.RuntimeProbeContext;
import io.cortexj.spi.runtime.RuntimeProbeResult;
import io.cortexj.spi.runtime.RuntimeProvider;
import io.cortexj.spi.runtime.RuntimeSupport;

public final class RemoteOpenAiProvider implements RuntimeProvider {

    static final String CONFIG_BASE_URL = "remote.baseUrl";
    static final String CONFIG_API_KEY = "remote.apiKey";

    private final RuntimeId id = RuntimeId.of("openai-remote");
    private final RuntimeDescriptor descriptor = RuntimeDescriptor.builder(id)
            .version("1.0")
            .format(ArtifactFormat.REMOTE)
            .device(DeviceType.REMOTE)
            .task(TaskCapability.TEXT_GENERATION)
            .task(TaskCapability.EMBEDDING)
            .task(TaskCapability.RERANKING)
            .minJavaFeatureVersion(8)
            .streamingSupported(true)
            .nativeRuntime(false)
            .threadSafety(io.cortexj.runtime.ThreadSafety.STATELESS)
            .maturity(RuntimeDescriptor.Maturity.STABLE)
            .metadata("protocol", "openai-compatible")
            .build();

    @Override
    public RuntimeId id() {
        return id;
    }

    @Override
    public RuntimeDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public RuntimeProbeResult probe(RuntimeProbeContext context) {
        String baseUrl = context.configurationValue(CONFIG_BASE_URL);
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            RuntimeCompatibilityException failure = new RuntimeCompatibilityException(
                    ErrorCode.RUNTIME_MISCONFIGURED,
                    "The openai-remote runtime requires an endpoint base URL.",
                    "Set CORTEXJ_REMOTE_BASE_URL, the system property cortexj.remote.base.url, "
                            + "or CortexJ.builder().remoteBaseUrl(...).");
            failure.detail("runtime", id.value());
            return RuntimeProbeResult.misconfigured("No remote runtime endpoint configured.", failure);
        }
        if (context.isOffline()) {
            return RuntimeProbeResult.degraded(
                    "remote endpoint configured (" + baseUrl + ") but unreachable in offline mode");
        }
        try {
            HttpJsonClient client = new HttpJsonClient();
            HttpJsonClient.Response response = client.get(baseUrl, "/v1/models", null);
            if (response.isOk()) {
                return RuntimeProbeResult.available("openai-compatible endpoint reachable: " + baseUrl);
            }
            return RuntimeProbeResult.degraded("endpoint responded with HTTP " + response.status);
        } catch (Exception e) {
            return RuntimeProbeResult.degraded("endpoint not reachable right now: " + e.getMessage());
        }
    }

    @Override
    public RuntimeSupport evaluate(RuntimeEvaluationRequest request) {
        if (request.artifact() != null && request.artifact().format() == ArtifactFormat.REMOTE) {
            return RuntimeSupport.full(DeviceType.REMOTE);
        }
        return RuntimeSupport.unsupported(io.cortexj.planner.PlanReason.negative(
                io.cortexj.planner.ReasonCode.FORMAT_UNSUPPORTED,
                "openai-remote executes models server-side and only accepts REMOTE artifacts"));
    }

    @Override
    public ModelRuntime open(RuntimeOpenRequest request) {
        String baseUrl = firstNonEmpty(request.platformConfiguration().get(CONFIG_BASE_URL),
                optionAsString(request, "baseUrl"),
                System.getenv("CORTEXJ_REMOTE_BASE_URL"),
                System.getProperty("cortexj.remote.base.url"));
        if (baseUrl == null) {
            RuntimeCompatibilityException failure = new RuntimeCompatibilityException(
                    ErrorCode.RUNTIME_MISCONFIGURED,
                    "openai-remote runtime has no base URL configured.",
                    "Set CORTEXJ_REMOTE_BASE_URL or CortexJ.builder().remoteBaseUrl(\"http://host:port\").");
            failure.detail("runtime", id.value());
            throw failure;
        }
        String apiKey = firstNonEmpty(request.platformConfiguration().get(CONFIG_API_KEY),
                optionAsString(request, "apiKey"),
                System.getenv("CORTEXJ_REMOTE_API_KEY"),
                System.getenv("OPENAI_API_KEY"));
        return new RemoteOpenAiRuntime(id, baseUrl, apiKey, request.executor(), request.eventListener());
    }

    private static String optionAsString(RuntimeOpenRequest request, String key) {
        Object value = request.runtimeOptions() == null ? null : request.runtimeOptions().get(key);
        return value instanceof String ? (String) value : null;
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }
}
