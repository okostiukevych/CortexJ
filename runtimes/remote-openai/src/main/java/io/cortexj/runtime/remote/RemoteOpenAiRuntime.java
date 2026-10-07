package io.cortexj.runtime.remote;

import com.fasterxml.jackson.databind.JsonNode;
import io.cortexj.ErrorCode;
import io.cortexj.error.InferenceException;
import io.cortexj.error.ModelLoadException;
import io.cortexj.inference.EmbeddingRequest;
import io.cortexj.inference.EmbeddingResponse;
import io.cortexj.inference.FinishReason;
import io.cortexj.inference.GenerationRequest;
import io.cortexj.inference.GenerationResponse;
import io.cortexj.inference.InferenceRequest;
import io.cortexj.inference.InferenceResponse;
import io.cortexj.inference.RerankingRequest;
import io.cortexj.inference.RerankingResponse;
import io.cortexj.inference.TextGenerationCapability;
import io.cortexj.inference.TokenUsage;
import io.cortexj.model.ModelCapabilities;
import io.cortexj.model.TaskCapability;
import io.cortexj.observability.PlatformEventListener;
import io.cortexj.runtime.RuntimeId;
import io.cortexj.spi.runtime.ModelRuntime;
import io.cortexj.spi.runtime.RuntimeModel;
import io.cortexj.spi.runtime.RuntimeModelRequest;
import io.cortexj.stream.TokenStream;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RemoteOpenAiRuntime implements ModelRuntime {

    private static final int SSE_POLL_MILLIS = 2_000;
    // audit #103: a generous timeout while waiting for the response headers -
    // a busy remote endpoint can take seconds to answer headers, and the old
    // 2s poll timeout failed such streams with false SocketTimeoutExceptions;
    // cancel() (which disconnects) remains the primary abort mechanism
    private static final int SSE_HEADER_TIMEOUT_MILLIS = 30_000;
    private static final long SSE_IDLE_CEILING_MILLIS = 300_000L;

    private static InferenceException closedDuringStream() {
        return new InferenceException(ErrorCode.INFERENCE_CANCELLED,
                "the model was closed while the stream was active",
                "Subscribe again after re-loading the model.");
    }

    private final RuntimeId runtimeId;
    private final String baseUrl;
    private final String apiKey;
    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private final PlatformEventListener eventListener;

    private final HttpJsonClient client;
    private final java.util.concurrent.atomic.AtomicBoolean runtimeClosed =
            new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.Set<HttpURLConnection> liveStreams =
            java.util.Collections.newSetFromMap(
                    new java.util.concurrent.ConcurrentHashMap<HttpURLConnection, Boolean>());

    RemoteOpenAiRuntime(RuntimeId runtimeId, String baseUrl, String apiKey, ExecutorService executor, PlatformEventListener eventListener, HttpJsonClient client) {
        this.runtimeId = runtimeId;
        this.client = client == null ? new HttpJsonClient() : client;
        this.baseUrl = HttpJsonClient.trimSlash(baseUrl);
        this.apiKey = apiKey;
        this.ownsExecutor = executor == null;
        this.executor = executor == null
                ? Executors.newFixedThreadPool(4,
                        io.cortexj.spi.platform.PlatformModule.daemonThreadFactory("cortexj-remote-io"))
                : executor;
        this.eventListener = eventListener == null ? PlatformEventListener.NOOP : eventListener;
    }

    @Override
    public RuntimeId runtimeId() {
        return runtimeId;
    }

    @Override
    public RuntimeModel load(RuntimeModelRequest request) {
        String model = modelIdFor(request);
        boolean rerankEnabled = Boolean.parseBoolean(
                String.valueOf(request.runtimeOptions().getOrDefault("enableRerank", "false")));
        return new RemoteModel(model, rerankEnabled);
    }

    private String modelIdFor(RuntimeModelRequest request) {
        Object override = request.runtimeOptions().get("model");
        if (override instanceof String && !((String) override).trim().isEmpty()) {
            return ((String) override).trim();
        }
        String fromArtifact = request.artifact() == null ? null : request.artifact().metadata().get("remoteModel");
        if (fromArtifact != null && !fromArtifact.isEmpty()) {
            return fromArtifact;
        }
        return request.model() == null ? "default" : request.model().canonicalId();
    }

    @Override
    public void close() {
        // audit M-8: disconnect every in-flight SSE connection first - socket
        // reads never respond to interrupts, so shutdownNow alone would leave
        // workers blocked for the full read timeout while the remote endpoint
        // keeps generating (and billing) server-side
        runtimeClosed.set(true);
        for (HttpURLConnection connection : liveStreams) {
            try {
                connection.disconnect();
            } catch (RuntimeException ignored) {
            }
        }
        if (ownsExecutor) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    final class RemoteModel implements RuntimeModel, TextGenerationCapability,
            io.cortexj.inference.EmbeddingCapability, io.cortexj.inference.RerankingCapability {

        private final String modelId;
        private final boolean rerankEnabled;
        private volatile boolean closed;

        RemoteModel(String modelId, boolean rerankEnabled) {
            this.modelId = modelId;
            this.rerankEnabled = rerankEnabled;
        }

        @Override
        public ModelCapabilities capabilities() {
            return ModelCapabilities.of(Collections.singleton(TaskCapability.TEXT_GENERATION), true, false, 8);
        }

        @Override
        public InferenceResponse execute(InferenceRequest request) {
            if (request.metadata().containsKey("task") && "embedding".equals(request.metadata().get("task"))) {
                EmbeddingResponse embedding = embed(EmbeddingRequest.texts(request.inputs()));
                List<String> outputs = new ArrayList<String>();
                for (float[] vector : embedding.vectors()) {
                    outputs.add(java.util.Arrays.toString(vector));
                }
                return new InferenceResponse(outputs, Collections.<String, Object>singletonMap("vectors", Integer.valueOf(embedding.vectors().size())));
            }
            GenerationResponse response = generate(GenerationRequest.prompt(request.singleInput()));
            return InferenceResponse.single(response.text());
        }

        @Override
        public CompletionStage<InferenceResponse> executeAsync(final InferenceRequest request) {
            if (executor == null) {
                return CompletableFuture.completedFuture(execute(request));
            }
            return CompletableFuture.supplyAsync(new java.util.function.Supplier<InferenceResponse>() {
                @Override
                public InferenceResponse get() {
                    return execute(request);
                }
            }, executor);
        }

        @Override
        public GenerationResponse generate(GenerationRequest request) {
            requireOpen("generate");
            Map<String, Object> payload = generationPayload(request, false);
            HttpJsonClient.Response response = postJson("/v1/chat/completions", payload);
            if (!response.isOk()) {
                throw remoteError("generation failed", response);
            }
            JsonNode root = client.fromJson(response.body, JsonNode.class);
            JsonNode choice = root.path("choices").path(0);
            String text = choice.path("message").path("content").asText("");
            FinishReason finish = finishReason(choice.path("finish_reason").asText("stop"));
            TokenUsage usage = usageOf(root.path("usage"));
            return new GenerationResponse(text, finish, usage, Collections.singletonMap("remoteModel", modelId));
        }

        @Override
        public TokenStream stream(GenerationRequest request) {
            requireOpen("stream");
            final Map<String, Object> payload = generationPayload(request, true);
            final io.cortexj.stream.internal.TokenStreamPublisher publisher = new io.cortexj.stream.internal.TokenStreamPublisher();
            // audit H-4: per-stream cancel plumbing - the cancel action wired into
            // the publisher disconnects the in-flight connection so the worker
            // stops reading (and the remote endpoint stops billing) immediately
            final java.util.concurrent.atomic.AtomicBoolean cancelled =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            final java.util.concurrent.atomic.AtomicReference<HttpURLConnection> activeConnection =
                    new java.util.concurrent.atomic.AtomicReference<HttpURLConnection>();
            publisher.onCancel(new Runnable() {
                @Override
                public void run() {
                    cancelled.set(true);
                    HttpURLConnection connection = activeConnection.get();
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            });
            final Runnable worker = new Runnable() {
                @Override
                public void run() {
                    try {
                        streamSse(publisher, payload, cancelled, activeConnection);
                    } catch (final io.cortexj.error.CortexJException e) {
                        if (cancelled.get()) {
                            return;
                        }
                        if (runtimeClosed.get()) {
                            // audit #95: close must deliver a terminal, not swallow it
                            publisher.fail(closedDuringStream());
                            return;
                        }
                        publisher.fail(e);
                    } catch (final Exception e) {
                        if (cancelled.get()) {
                            return;
                        }
                        if (runtimeClosed.get()) {
                            publisher.fail(closedDuringStream());
                            return;
                        }
                        publisher.fail(new InferenceException(ErrorCode.INFERENCE_STREAM_FAILED,
                                "remote stream failed: " + e.getMessage(), e));
                    }
                }
            };
            if (executor != null) {
                try {
                    executor.execute(worker);
                } catch (java.util.concurrent.RejectedExecutionException saturated) {
                    publisher.fail(new InferenceException(ErrorCode.INFERENCE_STREAM_FAILED,
                            "Stream rejected: the IO executor queue is saturated ("
                                    + saturated.getMessage() + ").",
                            "Retry when in-flight streams drain or reduce concurrent streams."));
                }
            } else {
                worker.run();
            }
            return publisher.stream();
        }

        private void streamSse(io.cortexj.stream.internal.TokenStreamPublisher publisher, Map<String, Object> payload,
                               java.util.concurrent.atomic.AtomicBoolean cancelled,
                               java.util.concurrent.atomic.AtomicReference<HttpURLConnection> activeConnection) throws IOException {
            if (cancelled.get()) {
                return;
            }
            if (runtimeClosed.get()) {
                // audit #95: a close must not swallow the terminal
                publisher.fail(closedDuringStream());
                return;
            }
            // audit #103: the poll read timeout (below) also fires while
            // waiting for the response headers - a busy remote endpoint can
            // legitimately take longer than one poll slice to answer. A header
            // timeout retries on a fresh connection until the deadline; the
            // short poll timeout stays in force everywhere so close()/cancel()
            // keep freeing workers within one poll window
            long headerDeadline = System.currentTimeMillis() + SSE_HEADER_TIMEOUT_MILLIS;
            while (true) {
                URL url = new URL(baseUrl + "/v1/chat/completions");
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                liveStreams.add(connection);
                activeConnection.set(connection);
                try {
                    connection.setRequestMethod("POST");
                    connection.setConnectTimeout(30_000);
                    // poll-style read timeout: a blocked socket read cannot be
                    // interrupted by close()/cancel() (fd close does not wake a
                    // blocked reader), so the loop wakes at short intervals and
                    // re-checks the cancel/close flags - the 300s ceiling below
                    // still bounds a silent-but-open stream
                    connection.setReadTimeout(SSE_POLL_MILLIS);
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Accept", "text/event-stream");
                    connection.setRequestProperty("Content-Type", "application/json");
                    if (apiKey != null && !apiKey.isEmpty()) {
                        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
                    }
                    byte[] body = client.toJson(payload).getBytes(StandardCharsets.UTF_8);
                    connection.setFixedLengthStreamingMode(body.length);
                    java.io.OutputStream output = connection.getOutputStream();
                    try {
                        output.write(body);
                    } finally {
                        output.close();
                    }
                    int status;
                    try {
                        status = connection.getResponseCode();
                    } catch (java.net.SocketTimeoutException headersNotAnswered) {
                        connection.disconnect();
                        liveStreams.remove(connection);
                        activeConnection.compareAndSet(connection, null);
                        if (cancelled.get()) {
                            return;
                        }
                        if (runtimeClosed.get()) {
                            publisher.fail(closedDuringStream());
                            return;
                        }
                        if (System.currentTimeMillis() >= headerDeadline) {
                            throw new InferenceException(ErrorCode.INFERENCE_STREAM_FAILED,
                                    "the remote endpoint did not answer the stream request within "
                                            + SSE_HEADER_TIMEOUT_MILLIS + " ms",
                                    "Check the remote endpoint health and its request queue.")
                                    .detail("endpoint", baseUrl);
                        }
                        continue;
                    }
                    if (status != 200) {
                        throw new InferenceException(ErrorCode.INFERENCE_STREAM_FAILED,
                                "remote stream request failed with HTTP " + status,
                                "Check the remote endpoint health and model id.")
                                .detail("status", String.valueOf(status));
                    }
                    readSseStream(connection.getInputStream(), publisher, cancelled);
                    return;
                } finally {
                    liveStreams.remove(connection);
                    activeConnection.compareAndSet(connection, null);
                    connection.disconnect();
                }
            }
        }

        private void readSseStream(InputStream stream, io.cortexj.stream.internal.TokenStreamPublisher publisher,
                                   java.util.concurrent.atomic.AtomicBoolean cancelled) throws IOException {
            // audit L-6: the reader is closed by try-with-resources - a mid-read
            // exception must not rely on the outer disconnect for cleanup
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                FinishReason finish = FinishReason.STOP;
                long inputTokens = -1;
                long outputTokens = -1;
                long lastActivity = System.currentTimeMillis();
                while (!cancelled.get() && !runtimeClosed.get()) {
                    String line;
                    try {
                        line = reader.readLine();
                    } catch (java.net.SocketTimeoutException idle) {
                        if (System.currentTimeMillis() - lastActivity >= SSE_IDLE_CEILING_MILLIS) {
                            throw new IOException("remote stream produced no data for "
                                    + SSE_IDLE_CEILING_MILLIS + " ms");
                        }
                        continue;
                    }
                    if (line == null) {
                        break;
                    }
                    lastActivity = System.currentTimeMillis();
                    if (line.isEmpty()) {
                        continue;
                    }
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if (data.equals("[DONE]")) {
                        break;
                    }
                    JsonNode chunk = client.fromJson(data, JsonNode.class);
                    JsonNode choice = chunk.path("choices").path(0);
                    String delta = choice.path("delta").path("content").asText("");
                    if (!delta.isEmpty()) {
                        publisher.publish(delta);
                    }
                    if (choice.has("finish_reason") && !choice.path("finish_reason").isNull()) {
                        finish = finishReason(choice.path("finish_reason").asText("stop"));
                    }
                    JsonNode usageNode = chunk.path("usage");
                    if (usageNode.has("prompt_tokens")) {
                        inputTokens = usageNode.path("prompt_tokens").asLong(-1);
                        outputTokens = usageNode.path("completion_tokens").asLong(-1);
                    }
                }
                if (cancelled.get()) {
                    // deliberately cancelled by the user: no terminal delivery
                    return;
                }
                if (runtimeClosed.get()) {
                    // audit #95: model/platform close mid-stream must terminate
                    // the subscriber instead of leaving it waiting forever
                    publisher.fail(closedDuringStream());
                    return;
                }
                publisher.complete(finish, inputTokens, outputTokens);
            }
        }

        @Override
        public EmbeddingResponse embed(EmbeddingRequest request) {
            requireOpen("embed");
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("model", modelId);
            payload.put("input", request.texts());
            HttpJsonClient.Response response = postJson("/v1/embeddings", payload);
            if (!response.isOk()) {
                throw remoteError("embedding failed", response);
            }
            JsonNode root = client.fromJson(response.body, JsonNode.class);
            List<float[]> vectors = new ArrayList<float[]>();
            for (JsonNode item : root.path("data")) {
                JsonNode embedding = item.path("embedding");
                float[] vector = new float[embedding.size()];
                for (int i = 0; i < embedding.size(); i++) {
                    vector[i] = (float) embedding.get(i).asDouble();
                }
                vectors.add(vector);
            }
            return new EmbeddingResponse(vectors);
        }

        @Override
        public RerankingResponse rerank(RerankingRequest request) {
            requireOpen("rerank");
            if (!rerankEnabled) {
                throw new InferenceException(ErrorCode.INFERENCE_UNSUPPORTED_TASK,
                        "Reranking is disabled for this remote model.",
                        "Enable it via runtime options: RuntimeOptions.forRuntime(\"openai-remote\").put(\"enableRerank\", true).");
            }
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("model", modelId);
            payload.put("query", request.query());
            payload.put("documents", request.documents());
            HttpJsonClient.Response response = postJson("/v1/rerank", payload);
            if (!response.isOk()) {
                throw remoteError("reranking failed", response);
            }
            JsonNode root = client.fromJson(response.body, JsonNode.class);
            List<RerankingResponse.ScoredDocument> scores = new ArrayList<RerankingResponse.ScoredDocument>();
            for (JsonNode item : root.path("results")) {
                scores.add(new RerankingResponse.ScoredDocument(
                        item.path("index").asInt(0), item.path("relevance_score").asDouble(0.0d)));
            }
            return new RerankingResponse(scores);
        }

        @Override
        public void close() {
            closed = true;
        }

        private void requireOpen(String operation) {
            if (closed) {
                throw new InferenceException(ErrorCode.INFERENCE_FAILED,
                        "Cannot " + operation + " on a closed remote model handle.",
                        "Create a new handle via CortexJ.models().load(...).");
            }
        }

        private Map<String, Object> generationPayload(GenerationRequest request, boolean stream) {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("model", modelId);
            List<Map<String, String>> messages = new ArrayList<Map<String, String>>();
            if (request.isChat()) {
                for (io.cortexj.model.ChatMessage message : request.messages()) {
                    messages.add(messagePayload(message.role().name().toLowerCase(), message.content()));
                }
            } else {
                messages.add(messagePayload("user", request.prompt()));
            }
            payload.put("messages", messages);
            if (request.temperature() != null) {
                payload.put("temperature", request.temperature());
            }
            if (request.maxTokens() != null) {
                payload.put("max_tokens", request.maxTokens());
            }
            if (request.topP() != null) {
                payload.put("top_p", request.topP());
            }
            if (request.seed() != null) {
                payload.put("seed", request.seed());
            }
            if (!request.stopSequences().isEmpty()) {
                payload.put("stop", request.stopSequences());
            }
            if (stream) {
                payload.put("stream", Boolean.TRUE);
                payload.put("stream_options", Collections.singletonMap("include_usage", Boolean.TRUE));
            }
            return payload;
        }

        private Map<String, String> messagePayload(String role, String content) {
            Map<String, String> message = new LinkedHashMap<String, String>();
            message.put("role", role);
            message.put("content", content);
            return message;
        }

        private HttpJsonClient.Response postJson(String path, Map<String, Object> payload) {
            try {
                return client.post(baseUrl, path, client.toJson(payload), apiKey);
            } catch (IOException e) {
                InferenceException failure = new InferenceException(ErrorCode.INFERENCE_FAILED,
                        "Remote runtime call to " + path + " failed: " + e.getMessage(),
                        "Verify the endpoint URL and network connectivity.", e);
                failure.detail("endpoint", baseUrl);
                throw failure;
            }
        }

        private InferenceException remoteError(String what, HttpJsonClient.Response response) {
            InferenceException failure = new InferenceException(ErrorCode.INFERENCE_FAILED,
                    "Remote runtime " + what + " with HTTP " + response.status + ": "
                            + abbreviate(response.body),
                    "Check the model id, endpoint configuration and API key.");
            failure.detail("status", String.valueOf(response.status));
            failure.detail("endpoint", baseUrl);
            return failure;
        }

        private String abbreviate(String body) {
            if (body == null) {
                return "";
            }
            return body.length() <= 300 ? body : body.substring(0, 300) + "...";
        }

        private TokenUsage usageOf(JsonNode usageNode) {
            if (!usageNode.has("prompt_tokens")) {
                return null;
            }
            return new TokenUsage(
                    usageNode.path("prompt_tokens").asLong(-1),
                    usageNode.path("completion_tokens").asLong(-1));
        }

        private FinishReason finishReason(String value) {
            if ("length".equals(value)) {
                return FinishReason.LENGTH;
            }
            if ("content_filter".equals(value)) {
                return FinishReason.CONTENT_FILTER;
            }
            if ("tool_calls".equals(value)) {
                return FinishReason.TOOL_CALL;
            }
            return "stop".equals(value) ? FinishReason.STOP : FinishReason.OTHER;
        }
    }
}
