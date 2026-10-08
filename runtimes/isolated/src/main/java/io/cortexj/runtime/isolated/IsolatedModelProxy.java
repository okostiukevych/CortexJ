package io.cortexj.runtime.isolated;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cortexj.ErrorCode;
import io.cortexj.error.CortexJException;
import io.cortexj.error.InferenceException;
import io.cortexj.inference.EmbeddingRequest;
import io.cortexj.inference.EmbeddingResponse;
import io.cortexj.inference.GenerationRequest;
import io.cortexj.inference.GenerationResponse;
import io.cortexj.inference.InferenceRequest;
import io.cortexj.inference.InferenceResponse;
import io.cortexj.inference.TextGenerationCapability;
import io.cortexj.model.ModelCapabilities;
import io.cortexj.spi.runtime.RuntimeModel;
import io.cortexj.stream.TokenStream;
import io.cortexj.stream.internal.TokenStreamPublisher;
import io.cortexj.worker.WorkerCodec;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Parent-side RuntimeModel proxy: every call is one protocol request to the
 * worker child (execute/generate/embed are blocking, stream subscribes to
 * protocol events through a {@link TokenStreamPublisher}).
 *
 * <p>Implemented as a JDK dynamic proxy so the model advertises exactly the
 * capability interfaces the child reported at load time (text generation
 * and/or embedding) - capabilityIfSupported() must not hand out interfaces
 * the hosted model does not implement.</p>
 */
final class IsolatedModelProxy implements InvocationHandler {

    private final WorkerChannel channel;
    private final ExecutorService executor;
    private final String modelToken;
    private final ModelCapabilities capabilities;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private IsolatedModelProxy(WorkerChannel channel, ExecutorService executor,
                              String modelToken, ModelCapabilities capabilities) {
        this.channel = channel;
        this.executor = executor;
        this.modelToken = modelToken;
        this.capabilities = capabilities;
    }

    static RuntimeModel create(WorkerChannel channel, ExecutorService executor, String modelToken,
                               ModelCapabilities capabilities, boolean textGeneration, boolean embedding) {
        IsolatedModelProxy handler = new IsolatedModelProxy(channel, executor, modelToken, capabilities);
        List<Class<?>> interfaces = new ArrayList<Class<?>>();
        interfaces.add(RuntimeModel.class);
        if (textGeneration) {
            interfaces.add(TextGenerationCapability.class);
        }
        if (embedding) {
            interfaces.add(io.cortexj.inference.EmbeddingCapability.class);
        }
        return (RuntimeModel) Proxy.newProxyInstance(
                IsolatedModelProxy.class.getClassLoader(),
                interfaces.toArray(new Class<?>[0]),
                handler);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if (method.getDeclaringClass() == Object.class) {
            if ("equals".equals(name)) {
                return Boolean.valueOf(proxy == args[0]);
            }
            if ("hashCode".equals(name)) {
                return Integer.valueOf(System.identityHashCode(proxy));
            }
            if ("toString".equals(name)) {
                return "IsolatedModelProxy(token=" + modelToken + ")";
            }
            return null;
        }
        if ("capabilities".equals(name)) {
            return capabilities;
        }
        if ("execute".equals(name)) {
            return execute((InferenceRequest) args[0]);
        }
        if ("executeAsync".equals(name)) {
            return executeAsync((InferenceRequest) args[0]);
        }
        if ("generate".equals(name)) {
            return generate((GenerationRequest) args[0]);
        }
        if ("generateAsync".equals(name)) {
            return generateAsync((GenerationRequest) args[0]);
        }
        if ("stream".equals(name)) {
            return stream((GenerationRequest) args[0]);
        }
        if ("embed".equals(name)) {
            return embed((EmbeddingRequest) args[0]);
        }
        if ("close".equals(name)) {
            close();
            return null;
        }
        throw new InferenceException(ErrorCode.INFERENCE_UNSUPPORTED_TASK,
                "isolated model proxy cannot dispatch method '" + name + "'");
    }

    // ------------------------------------------------------------------
    // operations
    // ------------------------------------------------------------------

    InferenceResponse execute(InferenceRequest request) {
        requireOpen("execute");
        ObjectNode payload = WorkerCodec.object();
        payload.put("modelToken", modelToken);
        payload.set("request", WorkerCodec.inferenceRequest(request));
        JsonNode reply = channel.call(channel.nextId(), "execute", payload);
        return WorkerCodec.parseInferenceResponse(reply.path("response"));
    }

    CompletionStage<InferenceResponse> executeAsync(final InferenceRequest request) {
        return supplyAsync(new java.util.function.Supplier<InferenceResponse>() {
            @Override
            public InferenceResponse get() {
                return execute(request);
            }
        });
    }

    GenerationResponse generate(GenerationRequest request) {
        requireOpen("generate");
        ObjectNode payload = WorkerCodec.object();
        payload.put("modelToken", modelToken);
        payload.set("request", WorkerCodec.generationRequest(request));
        JsonNode reply = channel.call(channel.nextId(), "generate", payload);
        return WorkerCodec.parseGenerationResponse(reply.path("response"));
    }

    CompletionStage<GenerationResponse> generateAsync(final GenerationRequest request) {
        return supplyAsync(new java.util.function.Supplier<GenerationResponse>() {
            @Override
            public GenerationResponse get() {
                return generate(request);
            }
        });
    }

    EmbeddingResponse embed(EmbeddingRequest request) {
        requireOpen("embed");
        ObjectNode payload = WorkerCodec.object();
        payload.put("modelToken", modelToken);
        payload.set("request", WorkerCodec.embeddingRequest(request));
        JsonNode reply = channel.call(channel.nextId(), "embed", payload);
        return WorkerCodec.parseEmbeddingResponse(reply.path("response"));
    }

    TokenStream stream(GenerationRequest request) {
        requireOpen("stream");
        final TokenStreamPublisher publisher = new TokenStreamPublisher();
        final long streamId = channel.nextId();
        channel.registerStream(streamId, new WorkerChannel.StreamEvents() {
            @Override
            public void onToken(String text) {
                publisher.publish(text);
            }

            @Override
            public void onComplete(io.cortexj.inference.FinishReason finishReason,
                                   io.cortexj.inference.TokenUsage usage) {
                publisher.complete(finishReason,
                        usage == null ? -1L : usage.inputTokens(),
                        usage == null ? -1L : usage.outputTokens());
            }

            @Override
            public void onError(CortexJException failure) {
                publisher.fail(failure);
            }
        });
        // cancel plumbing (audit H-4 pattern): the subscription cancel tells
        // the worker to abort the upstream stream and stops the pump
        publisher.onCancel(new Runnable() {
            @Override
            public void run() {
                channel.cancelStream(streamId);
            }
        });
        ObjectNode payload = WorkerCodec.object();
        payload.put("modelToken", modelToken);
        payload.set("request", WorkerCodec.generationRequest(request));
        // audit 2026-10-08 (#153): the ack wait must not block the caller -
        // a cancel arriving before the child acknowledges used to pile behind
        // future.get() for up to the full request timeout. The publisher
        // replays a pre-subscribe terminal, so async refusal is lossless.
        final ObjectNode ackPayload = payload;
        Runnable awaitAck = new Runnable() {
            @Override
            public void run() {
                try {
                    channel.call(streamId, "stream", ackPayload);
                } catch (CortexJException refused) {
                    channel.unregisterStream(streamId);
                    publisher.fail(refused);
                } catch (RuntimeException unexpected) {
                    channel.unregisterStream(streamId);
                    publisher.fail(new InferenceException(ErrorCode.INFERENCE_FAILED,
                            "isolated stream setup failed: " + unexpected.getMessage(), unexpected));
                }
            }
        };
        if (executor == null) {
            awaitAck.run();
        } else {
            try {
                executor.execute(awaitAck);
            } catch (java.util.concurrent.RejectedExecutionException saturated) {
                channel.unregisterStream(streamId);
                publisher.fail(new InferenceException(ErrorCode.INFERENCE_FAILED,
                        "the isolated runtime IO executor rejected the stream request (queue saturated)",
                        "Retry when in-flight requests drain."));
            }
        }
        return publisher.stream();
    }

    void close() {
        if (closed.compareAndSet(false, true)) {
            ObjectNode payload = WorkerCodec.object();
            payload.put("modelToken", modelToken);
            try {
                channel.call(channel.nextId(), "closeModel", payload);
            } catch (CortexJException alreadyDead) {
                // the child is gone; nothing left to close
            }
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void requireOpen(String operation) {
        if (closed.get()) {
            throw new InferenceException(ErrorCode.INFERENCE_FAILED,
                    "Cannot " + operation + " on a closed isolated model handle (token " + modelToken + ").",
                    "Load a new model handle from the runtime.");
        }
    }

    private <T> CompletionStage<T> supplyAsync(final java.util.function.Supplier<T> supplier) {
        if (executor == null) {
            return CompletableFuture.completedFuture(supplier.get());
        }
        try {
            return CompletableFuture.supplyAsync(supplier, executor);
        } catch (RejectedExecutionException saturated) {
            CompletableFuture<T> failed = new CompletableFuture<T>();
            failed.completeExceptionally(new InferenceException(ErrorCode.INFERENCE_FAILED,
                    "the isolated runtime IO executor rejected the request (queue saturated)",
                    "Retry when in-flight requests drain."));
            return failed;
        }
    }
}
