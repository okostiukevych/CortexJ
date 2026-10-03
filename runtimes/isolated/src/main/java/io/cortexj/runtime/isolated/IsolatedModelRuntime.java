package io.cortexj.runtime.isolated;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cortexj.error.CortexJException;
import io.cortexj.model.ModelCapabilities;
import io.cortexj.runtime.RuntimeId;
import io.cortexj.spi.runtime.ModelRuntime;
import io.cortexj.spi.runtime.RuntimeModel;
import io.cortexj.spi.runtime.RuntimeModelRequest;
import io.cortexj.worker.WorkerCodec;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Parent-side session over one worker channel. open() sends the protocol
 * "open" once per session (the provider reference-counts sessions: the child
 * hosts exactly one at a time); load() returns a proxy model. Closing the
 * session closes every model token, sends "close", and - for the last live
 * session - is what allows the next open() to reopen the child.
 */
final class IsolatedModelRuntime implements ModelRuntime {

    private final IsolatedRuntimeProvider provider;
    private final WorkerChannel channel;
    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private final Set<String> modelTokens =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final AtomicBoolean closed = new AtomicBoolean(false);

    IsolatedModelRuntime(IsolatedRuntimeProvider provider, WorkerChannel channel, ExecutorService executor) {
        this.provider = provider;
        this.channel = channel;
        this.ownsExecutor = executor == null;
        this.executor = executor == null
                ? Executors.newFixedThreadPool(4,
                        io.cortexj.spi.platform.PlatformModule.daemonThreadFactory("cortexj-isolated-io"))
                : executor;
    }

    @Override
    public RuntimeId runtimeId() {
        return provider.id();
    }

    @Override
    public RuntimeModel load(RuntimeModelRequest request) {
        ObjectNode payload = WorkerCodec.object();
        payload.set("descriptor", WorkerCodec.modelDescriptor(request.model()));
        payload.set("artifact", WorkerCodec.artifact(request.artifact()));
        if (request.artifactPath() != null) {
            payload.put("artifactPath", request.artifactPath().toAbsolutePath().toString());
        }
        payload.put("device", request.device() == null ? "CPU" : request.device().name());
        payload.set("options", WorkerCodec.mapper().valueToTree(request.runtimeOptions()));
        JsonNode reply = channel.call(channel.nextId(), "load", payload);
        String modelToken = reply.path("modelToken").asText("");
        if (modelToken.isEmpty()) {
            throw new CortexJException(io.cortexj.ErrorCode.INFERENCE_FAILED,
                    "worker load reply carried no model token");
        }
        modelTokens.add(modelToken);
        ModelCapabilities capabilities = WorkerCodec.parseModelCapabilities(reply.path("capabilities"));
        boolean textGeneration = reply.path("textGeneration").asBoolean(false);
        boolean embedding = reply.path("embedding").asBoolean(false);
        return IsolatedModelProxy.create(channel, executor, modelToken, capabilities, textGeneration, embedding);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            // close every model token best-effort (the ladder already applies
            // when the child itself is gone)
            for (String token : modelTokens) {
                ObjectNode payload = WorkerCodec.object();
                payload.put("modelToken", token);
                try {
                    channel.call(channel.nextId(), "closeModel", payload);
                } catch (CortexJException childGone) {
                    break;
                }
            }
            modelTokens.clear();
            boolean lastSession = provider.sessionClosed(this);
            if (lastSession) {
                try {
                    channel.call(channel.nextId(), "close", WorkerCodec.object());
                } catch (CortexJException childGone) {
                    // child is dead; the provider will respawn on demand
                }
            }
            if (ownsExecutor) {
                executor.shutdown();
                try {
                    if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                        executor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    executor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
