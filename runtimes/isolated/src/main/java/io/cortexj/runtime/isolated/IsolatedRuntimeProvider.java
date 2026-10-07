package io.cortexj.runtime.isolated;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cortexj.ErrorCode;
import io.cortexj.error.CortexJException;
import io.cortexj.error.RuntimeUnavailableException;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.runtime.DeviceType;
import io.cortexj.runtime.RuntimeDescriptor;
import io.cortexj.runtime.RuntimeId;
import io.cortexj.runtime.ThreadSafety;
import io.cortexj.spi.runtime.ModelRuntime;
import io.cortexj.spi.runtime.RuntimeEvaluationRequest;
import io.cortexj.spi.runtime.RuntimeOpenRequest;
import io.cortexj.spi.runtime.RuntimeProbeContext;
import io.cortexj.spi.runtime.RuntimeProbeResult;
import io.cortexj.spi.runtime.RuntimeProvider;
import io.cortexj.spi.runtime.RuntimeSupport;
import io.cortexj.worker.WorkerCodec;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Parent-side runtime provider that hosts a target runtime inside an
 * isolated worker child JVM (spec section 127, GitHub issue #88).
 *
 * <p>Opt-in only - it is never registered via META-INF/services. Wire it
 * programmatically per target runtime:</p>
 *
 * <pre>{@code
 * CortexJ.builder()
 *     .runtimeProvider(new IsolatedRuntimeProvider("onnx"))
 *     .build();
 * }</pre>
 *
 * <p>The provider id prefixes the child's runtime id ("worker-onnx") so the
 * planner sees a distinct runtime. The parent-side descriptor is translated
 * from the child's handshake+probe: same formats/tasks/devices, but always
 * {@link ThreadSafety#STATELESS} (execution is a stateless pipe) and marked
 * with worker metadata. A dead child (crash, OOM, native abort) is detected
 * on the next request, typed as an {@link io.cortexj.error.InferenceException}
 * that names the worker process, torn down with the destroy ladder and
 * respawned on the next probe/open.</p>
 */
public final class IsolatedRuntimeProvider implements RuntimeProvider, AutoCloseable {

    /** Prefix applied to the hosted child runtime id ("onnx" -> "worker-onnx"). */
    public static final String ID_PREFIX = "worker-";

    private static final Logger LOG = Logger.getLogger(IsolatedRuntimeProvider.class.getName());

    private final String targetRuntimeId;
    private final RuntimeId id;
    private final Object channelLock = new Object();
    private final Set<IsolatedModelRuntime> openSessions =
            Collections.newSetFromMap(new ConcurrentHashMap<IsolatedModelRuntime, Boolean>());
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile WorkerChannel channel;
    private volatile RuntimeDescriptor cachedDescriptor;

    public IsolatedRuntimeProvider(String targetRuntimeId) {
        if (targetRuntimeId == null || targetRuntimeId.trim().isEmpty()) {
            throw new IllegalArgumentException("target runtime id must not be empty");
        }
        this.targetRuntimeId = targetRuntimeId.trim();
        this.id = RuntimeId.of(ID_PREFIX + this.targetRuntimeId.toLowerCase());
    }

    /** The child runtime id this provider hosts ("onnx" for a worker-onnx provider). */
    public String targetRuntimeId() {
        return targetRuntimeId;
    }

    @Override
    public RuntimeId id() {
        return id;
    }

    @Override
    public RuntimeDescriptor descriptor() {
        RuntimeDescriptor descriptor = cachedDescriptor;
        if (descriptor != null) {
            return descriptor;
        }
        try {
            descriptor = probeChildDescriptor(ensureChannel(), false, Collections.<String, String>emptyMap());
            cachedDescriptor = descriptor;
            return descriptor;
        } catch (CortexJException spawnFailure) {
            LOG.warning("isolated worker descriptor lookup failed (reporting a placeholder): "
                    + spawnFailure.getMessage());
            return placeholderDescriptor();
        }
    }

    @Override
    public RuntimeProbeResult probe(RuntimeProbeContext context) {
        try {
            WorkerChannel active = ensureChannel();
            ObjectNode payload = WorkerCodec.object();
            payload.put("offline", context.isOffline());
            payload.set("configuration", WorkerCodec.mapper().valueToTree(context.configuration()));
            JsonNode reply = active.call(active.nextId(), "probe", payload);
            cachedDescriptor = translatedDescriptor(WorkerCodec.parseRuntimeDescriptor(reply.path("descriptor")));
            boolean available = reply.path("available").asBoolean(false);
            String message = reply.path("message").asText("worker probe finished");
            if (!available) {
                CortexJException cause = reply.has("error")
                        ? WorkerCodec.toException(reply.path("error"), ErrorCode.RUNTIME_NOT_INSTALLED, message)
                        : null;
                if (cause == null) {
                    return RuntimeProbeResult.unavailable(message);
                }
                return RuntimeProbeResult.unavailable(message, cause);
            }
            io.cortexj.runtime.RuntimeHealth health =
                    WorkerCodec.runtimeHealth(reply.path("health").asText("AVAILABLE"));
            if (health == io.cortexj.runtime.RuntimeHealth.DEGRADED) {
                return RuntimeProbeResult.degraded(message);
            }
            return RuntimeProbeResult.available(message);
        } catch (CortexJException failure) {
            return RuntimeProbeResult.unavailable(
                    "isolated worker probe failed for runtime '" + targetRuntimeId + "': "
                            + failure.getMessage(),
                    failure);
        } catch (Throwable unexpected) {
            RuntimeUnavailableException wrapped = new RuntimeUnavailableException(
                    ErrorCode.RUNTIME_UNAVAILABLE,
                    "isolated worker probe failed unexpectedly: " + unexpected,
                    null, unexpected);
            return RuntimeProbeResult.unavailable(wrapped.getMessage(), wrapped);
        }
    }

    @Override
    public RuntimeSupport evaluate(RuntimeEvaluationRequest request) {
        ArtifactFormat format = request.artifact() == null ? null : request.artifact().format();
        RuntimeDescriptor descriptor = descriptor();
        if (format != null && format.isExecutableWeights() && descriptor.formats().contains(format)) {
            DeviceType device = descriptor.devices().isEmpty()
                    ? DeviceType.CPU
                    : descriptor.devices().iterator().next();
            return RuntimeSupport.full(device);
        }
        return RuntimeSupport.unsupported(io.cortexj.planner.PlanReason.negative(
                io.cortexj.planner.ReasonCode.FORMAT_UNSUPPORTED,
                id.value() + " executes " + descriptor.formats() + " but got " + format));
    }

    @Override
    public ModelRuntime open(RuntimeOpenRequest request) {
        if (closed.get()) {
            throw new RuntimeUnavailableException(ErrorCode.RUNTIME_UNAVAILABLE,
                    "the isolated runtime provider is closed",
                    "Create a new IsolatedRuntimeProvider instance.");
        }
        WorkerChannel active = ensureChannel();
        ObjectNode payload = WorkerCodec.object();
        payload.set("descriptor", WorkerCodec.modelDescriptor(request.model()));
        payload.set("artifact", WorkerCodec.artifact(request.artifact()));
        if (request.artifactPath() != null) {
            payload.put("artifactPath", request.artifactPath().toAbsolutePath().toString());
        }
        payload.put("device", request.device() == null ? "CPU" : request.device().name());
        payload.set("options", WorkerCodec.mapper().valueToTree(request.runtimeOptions()));
        // audit #98: the child env allowlist deliberately withholds credential
        // variables, and the open payload must not smuggle them in through the
        // back door either - a child-hosted runtime that prints its config (a
        // common native-runtime habit) would leak the key into parent logs
        payload.set("headers", WorkerCodec.mapper().valueToTree(
                sanitizeForChild(request.platformConfiguration())));
        active.call(active.nextId(), "open", payload);
        IsolatedModelRuntime runtime = new IsolatedModelRuntime(this, active, request.executor());
        openSessions.add(runtime);
        return runtime;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            for (IsolatedModelRuntime session : openSessions) {
                try {
                    session.close();
                } catch (RuntimeException ignored) {
                    // teardown must not fail on one broken session
                }
            }
            openSessions.clear();
            WorkerChannel active = channel;
            channel = null;
            if (active != null) {
                active.shutdown();
            }
        }
    }

    // ------------------------------------------------------------------
    // channel management
    // ------------------------------------------------------------------

    static java.util.Map<String, Object> sanitizeForChild(java.util.Map<String, ?> configuration) {
        java.util.Map<String, Object> safe = new java.util.LinkedHashMap<String, Object>();
        for (java.util.Map.Entry<String, ?> entry : configuration.entrySet()) {
            if (isCredentialKey(entry.getKey())) {
                continue;
            }
            safe.put(entry.getKey(), entry.getValue());
        }
        return safe;
    }

    private static boolean isCredentialKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(java.util.Locale.ROOT)
                .replace('.', '_').replace('-', '_');
        return normalized.endsWith("apikey")
                || normalized.endsWith("api_key")
                || normalized.endsWith("secret")
                || normalized.endsWith("password")
                || normalized.endsWith("token");
    }

    private WorkerChannel ensureChannel() {
        WorkerChannel active = channel;
        if (active != null && !active.isDead()) {
            return active;
        }
        synchronized (channelLock) {
            active = channel;
            if (active != null && !active.isDead()) {
                return active;
            }
            WorkerChannel fresh = WorkerChannel.start(targetRuntimeId);
            channel = fresh;
            return fresh;
        }
    }

    /** Called by a closing session; true when the child hosted session is free again. */
    boolean sessionClosed(IsolatedModelRuntime session) {
        openSessions.remove(session);
        return openSessions.isEmpty();
    }

    private RuntimeDescriptor probeChildDescriptor(WorkerChannel active, boolean offline,
                                                   java.util.Map<String, String> configuration) {
        ObjectNode payload = WorkerCodec.object();
        payload.put("offline", offline);
        payload.set("configuration", WorkerCodec.mapper().valueToTree(configuration));
        JsonNode reply = active.call(active.nextId(), "probe", payload);
        return translatedDescriptor(WorkerCodec.parseRuntimeDescriptor(reply.path("descriptor")));
    }

    private RuntimeDescriptor translatedDescriptor(RuntimeDescriptor child) {
        RuntimeDescriptor.Builder builder = RuntimeDescriptor.builder(id)
                .version(child.version());
        for (ArtifactFormat format : child.formats()) {
            builder.format(format);
        }
        for (DeviceType device : child.devices()) {
            builder.device(device);
        }
        for (io.cortexj.model.TaskCapability task : child.tasks()) {
            builder.task(task);
        }
        builder.minJavaFeatureVersion(child.minJavaFeatureVersion());
        builder.streamingSupported(child.isStreamingSupported());
        builder.structuredOutputNative(child.isStructuredOutputNative());
        builder.nativeRuntime(child.isNativeRuntime());
        builder.threadSafety(ThreadSafety.STATELESS);
        builder.maturity(child.maturity());
        for (java.util.Map.Entry<String, String> entry : child.metadata().entrySet()) {
            builder.metadata(entry.getKey(), entry.getValue());
        }
        builder.metadata("worker.target", targetRuntimeId);
        builder.metadata("worker.mode", "isolated");
        return builder.build();
    }

    private RuntimeDescriptor placeholderDescriptor() {
        return RuntimeDescriptor.builder(id)
                .version("0.0.0")
                .format(ArtifactFormat.UNKNOWN)
                .device(DeviceType.CPU)
                .task(io.cortexj.model.TaskCapability.EMBEDDING)
                .minJavaFeatureVersion(8)
                .streamingSupported(false)
                .nativeRuntime(false)
                .threadSafety(ThreadSafety.STATELESS)
                .maturity(RuntimeDescriptor.Maturity.EXPERIMENTAL)
                .metadata("worker.target", targetRuntimeId)
                .metadata("worker.error", "worker child could not be spawned; probe() reports the cause")
                .build();
    }

    // ------------------------------------------------------------------
    // test handles (package-private, used by the isolated tests only)
    // ------------------------------------------------------------------

    WorkerChannel currentChannelForTesting() {
        return channel;
    }
}
