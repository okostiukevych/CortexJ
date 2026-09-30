package io.cortexj.runtime.llamacpp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cortexj.ErrorCode;
import io.cortexj.error.CortexJException;
import io.cortexj.error.InferenceException;
import io.cortexj.inference.FinishReason;
import io.cortexj.inference.GenerationRequest;
import io.cortexj.inference.GenerationResponse;
import io.cortexj.inference.InferenceRequest;
import io.cortexj.inference.InferenceResponse;
import io.cortexj.stream.StreamCompletion;
import io.cortexj.inference.TextGenerationCapability;
import io.cortexj.inference.TokenUsage;
import io.cortexj.model.ModelCapabilities;
import io.cortexj.model.TaskCapability;
import io.cortexj.spi.runtime.RuntimeModel;
import io.cortexj.stream.StreamSubscription;
import io.cortexj.stream.Token;
import io.cortexj.stream.TokenConsumer;
import io.cortexj.stream.TokenStream;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class LlamaServerModel implements RuntimeModel, TextGenerationCapability {

    private static final int CONNECT_TIMEOUT = 15_000;
    private static final int READ_TIMEOUT = 300_000;
    private static final int SSE_POLL_MILLIS = 2_000;
    private static final long SSE_IDLE_CEILING_MILLIS = 300_000L;

    private final SharedEndpointRegistry.SharedEndpoint endpoint;
    private final ExecutorService sseExecutor;
    private final boolean ownsExecutor;
    private final ObjectMapper mapper = new ObjectMapper();
    private final java.util.concurrent.atomic.AtomicBoolean closed =
            new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.Set<LlamaServerTokenStream> liveStreams =
            java.util.Collections.newSetFromMap(
                    new java.util.concurrent.ConcurrentHashMap<LlamaServerTokenStream, Boolean>());

    LlamaServerModel(SharedEndpointRegistry.SharedEndpoint endpoint, ExecutorService executor) {
        this.endpoint = endpoint;
        this.ownsExecutor = executor == null;
        this.sseExecutor = executor == null
                ? Executors.newSingleThreadExecutor(
                        io.cortexj.spi.platform.PlatformModule.daemonThreadFactory("cortexj-llama-sse"))
                : executor;
    }

    @Override
    public ModelCapabilities capabilities() {
        return ModelCapabilities.of(java.util.Collections.singleton(TaskCapability.TEXT_GENERATION),
                true, false, 8);
    }

    @Override
    public InferenceResponse execute(InferenceRequest request) {
        GenerationResponse response = generate(GenerationRequest.prompt(request.singleInput()));
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        if (response.usage() != null) {
            metadata.put("outputTokens", Long.valueOf(response.usage().outputTokens()));
        }
        return new InferenceResponse(
                java.util.Collections.singletonList(response.text()), metadata);
    }

    @Override
    public java.util.concurrent.CompletionStage<InferenceResponse> executeAsync(
            final InferenceRequest request) {
        return java.util.concurrent.CompletableFuture.completedFuture(execute(request));
    }

    @Override
    public GenerationResponse generate(GenerationRequest request) {
        requireOpen();
        try {
            Map<String, Object> payload = chatPayload(request, false);
            String body = postJson("/v1/chat/completions", payload, true);
            JsonNode root = mapper.readTree(body);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.size() == 0) {
                throw new InferenceException(ErrorCode.INFERENCE_FAILED,
                        "llama-server returned no completions",
                        "Check the llama-server logs and the model file.");
            }
            String text = choices.get(0).path("message").path("content").asText("");
            TokenUsage usage = usageOf(root.path("usage"));
            return new GenerationResponse(text, FinishReason.STOP, usage, null);
        } catch (InferenceException e) {
            throw e;
        } catch (IOException e) {
            InferenceException failure = new InferenceException(ErrorCode.INFERENCE_FAILED,
                    "llama-server generation failed: " + e.getMessage(),
                    "Check that the managed llama-server process is alive.");
            failure.detail("endpoint", endpoint.baseUrl());
            throw failure;
        }
    }

    @Override
    public TokenStream stream(GenerationRequest request) {
        requireOpen();
        return new LlamaServerTokenStream(request);
    }

    private final class LlamaServerTokenStream implements TokenStream {

        private final GenerationRequest request;
        private final AtomicBoolean subscribed = new AtomicBoolean(false);
        private final AtomicLong emitted = new AtomicLong();
        // audit H-3: the cancelled flag is now actually read by the SSE loop,
        // and cancel()/close() disconnect the in-flight connection so the
        // single SSE thread is released instead of reading a dropped completion
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private volatile HttpURLConnection activeConnection;

        LlamaServerTokenStream(GenerationRequest request) {
            this.request = request;
        }

        @Override
        public StreamSubscription subscribe(final TokenConsumer consumer) {
            if (consumer == null) {
                throw new IllegalArgumentException("Token consumer must not be null");
            }
            if (!subscribed.compareAndSet(false, true)) {
                throw new IllegalStateException("Token stream supports a single subscription");
            }
            liveStreams.add(LlamaServerTokenStream.this);
            sseExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    runSse(consumer);
                }
            });
            return new StreamSubscription() {
                private final AtomicBoolean subscriptionCancelled = new AtomicBoolean(false);

                @Override
                public void cancel() {
                    if (subscriptionCancelled.compareAndSet(false, true)) {
                        abort();
                    }
                }

                @Override
                public boolean isCancelled() {
                    return subscriptionCancelled.get();
                }
            };
        }

        private void abort() {
            cancelled.set(true);
            HttpURLConnection connection = activeConnection;
            if (connection != null) {
                connection.disconnect();
            }
        }

        private void runSse(TokenConsumer consumer) {
            HttpURLConnection connection = null;
            try {
                if (cancelled.get() || closed.get()) {
                    return;
                }
                URL url = new URL(endpoint.baseUrl() + "/v1/chat/completions");
                connection = (HttpURLConnection) url.openConnection();
                activeConnection = connection;
                connection.setConnectTimeout(CONNECT_TIMEOUT);
                // poll-style read timeout: a blocked socket read cannot be
                // interrupted by cancel()/close() (fd close does not wake a
                // blocked reader), so the loop wakes at short intervals and
                // re-checks the cancel/close flags - the ceiling below still
                // bounds a silent-but-open stream
                connection.setReadTimeout(SSE_POLL_MILLIS);
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("Accept", "text/event-stream");
                byte[] body = mapper.writeValueAsBytes(chatPayload(request, true));
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body);
                }
                if (connection.getResponseCode() != 200) {
                    throw new IOException("HTTP " + connection.getResponseCode()
                            + " from llama-server streaming endpoint");
                }
                TokenUsage usage = TokenUsage.unknown();
                FinishReason finishReason = FinishReason.STOP;
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        connection.getInputStream(), StandardCharsets.UTF_8))) {
                    long lastActivity = System.currentTimeMillis();
                    while (!cancelled.get() && !closed.get()) {
                        String line;
                        try {
                            line = reader.readLine();
                        } catch (java.net.SocketTimeoutException idle) {
                            if (System.currentTimeMillis() - lastActivity >= SSE_IDLE_CEILING_MILLIS) {
                                throw new IOException("llama-server stream produced no data for "
                                        + SSE_IDLE_CEILING_MILLIS + " ms");
                            }
                            continue;
                        }
                        if (line == null) {
                            break;
                        }
                        lastActivity = System.currentTimeMillis();
                        if (!line.startsWith("data:")) {
                            continue;
                        }
                        String data = line.substring(5).trim();
                        if (data.equals("[DONE]")) {
                            break;
                        }
                        JsonNode chunk = mapper.readTree(data);
                        JsonNode delta = chunk.path("choices").path(0).path("delta").path("content");
                        if (!delta.isMissingNode() && !delta.isNull()) {
                            String token = delta.asText("");
                            if (!token.isEmpty()) {
                                emitted.incrementAndGet();
                                consumer.onToken(new Token(token, (int) emitted.get(),
                                        java.util.Collections.<String, String>emptyMap()));
                            }
                        }
                        JsonNode stop = chunk.path("choices").path(0).path("finish_reason");
                        if (stop.isTextual()) {
                            finishReason = finishReasonOf(stop.asText());
                        }
                        JsonNode chunkUsage = chunk.path("usage");
                        if (!chunkUsage.isMissingNode() && !chunkUsage.isNull()) {
                            usage = usageOf(chunkUsage);
                        }
                    }
                }
                if (cancelled.get() || closed.get()) {
                    return;
                }
                consumer.onComplete(new StreamCompletion(finishReason, usage));
            } catch (Exception e) {
                if (cancelled.get() || closed.get()) {
                    // deliberately cancelled or closed: no error delivery, thread released
                    return;
                }
                CortexJException failure = e instanceof CortexJException
                        ? (CortexJException) e
                        : new InferenceException(ErrorCode.INFERENCE_FAILED,
                                "llama-server streaming failed: " + e.getMessage(),
                                "Check that the managed llama-server process is alive.");
                failure.detail("endpoint", endpoint.baseUrl());
                consumer.onError(failure);
            } finally {
                liveStreams.remove(LlamaServerTokenStream.this);
                activeConnection = null;
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }

        @Override
        public void close() {
            abort();
        }
    }

    private Map<String, Object> chatPayload(GenerationRequest request, boolean stream) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("model", "local");
        java.util.List<Map<String, String>> messages = new java.util.ArrayList<Map<String, String>>();
        for (io.cortexj.model.ChatMessage message : request.messages()) {
            Map<String, String> mapped = new LinkedHashMap<String, String>();
            mapped.put("role", message.role().name().toLowerCase(java.util.Locale.ROOT));
            mapped.put("content", message.content());
            messages.add(mapped);
        }
        if (messages.isEmpty()) {
            messages.add(message("user", request.prompt()));
        }
        payload.put("messages", messages);
        payload.put("stream", Boolean.valueOf(stream));
        if (request.temperature() != null) {
            payload.put("temperature", request.temperature());
        }
        if (request.maxTokens() != null) {
            payload.put("max_tokens", request.maxTokens());
        }
        if (request.topP() != null) {
            payload.put("top_p", request.topP());
        }
        if (stream) {
            payload.put("stream_options", java.util.Collections.singletonMap("include_usage", true));
        }
        return payload;
    }

    private static Map<String, String> message(String role, String content) {
        Map<String, String> mapped = new LinkedHashMap<String, String>();
        mapped.put("role", role);
        mapped.put("content", content == null ? "" : content);
        return mapped;
    }

    private String postJson(String path, Object payload, boolean expectOk) throws IOException {
        URL url = new URL(endpoint.baseUrl() + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT);
            connection.setReadTimeout(READ_TIMEOUT);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            byte[] body = mapper.writeValueAsBytes(payload);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            int status = connection.getResponseCode();
            if (status != 200) {
                throw new IOException("HTTP " + status + " from llama-server");
            }
            return readAll(connection.getInputStream());
        } finally {
            connection.disconnect();
        }
    }

    private static String readAll(java.io.InputStream input) throws IOException {
        // audit L-6: explicit close - a mid-read exception must not leak the
        // response stream to the outer disconnect
        try (java.io.InputStream in = input) {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) >= 0) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static TokenUsage usageOf(JsonNode usage) {
        if (usage.isMissingNode() || usage.isNull()) {
            return TokenUsage.unknown();
        }
        long input = usage.path("prompt_tokens").asLong(-1L);
        long output = usage.path("completion_tokens").asLong(-1L);
        return input < 0 && output < 0 ? TokenUsage.unknown() : new TokenUsage(
                Math.max(input, 0L), Math.max(output, 0L));
    }

    private static FinishReason finishReasonOf(String value) {
        if ("length".equalsIgnoreCase(value)) {
            return FinishReason.LENGTH;
        }
        if ("content_filter".equalsIgnoreCase(value)) {
            return FinishReason.CONTENT_FILTER;
        }
        if ("tool_calls".equalsIgnoreCase(value) || "function_call".equalsIgnoreCase(value)) {
            return FinishReason.TOOL_CALL;
        }
        return FinishReason.STOP;
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new InferenceException(ErrorCode.INFERENCE_FAILED,
                    "model is closed", "Reopen the model handle.");
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            // audit M-8: abort every in-flight SSE stream first - socket reads
            // never respond to interrupts, so a plain shutdown would leave the
            // single SSE thread blocked for the full read timeout (and the
            // server-side generation running) when the endpoint is shared
            for (LlamaServerTokenStream stream : liveStreams) {
                stream.abort();
            }
            endpoint.release();
            if (ownsExecutor) {
                sseExecutor.shutdown();
                try {
                    if (!sseExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        sseExecutor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    sseExecutor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
