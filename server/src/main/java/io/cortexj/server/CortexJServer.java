package io.cortexj.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cortexj.CortexJ;
import io.cortexj.error.CortexJException;
import io.cortexj.inference.EmbeddingCapability;
import io.cortexj.inference.EmbeddingRequest;
import io.cortexj.inference.EmbeddingResponse;
import io.cortexj.inference.GenerationRequest;
import io.cortexj.inference.GenerationResponse;
import io.cortexj.inference.TextGenerationCapability;
import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelReference;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Optional embedded server mode (spec 60): exposes an OpenAI-compatible local
 * endpoint on top of a selected runtime.
 */
public final class CortexJServer {

    private final CortexJ platform;
    private final ModelHandle model;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private com.sun.net.httpserver.HttpServer httpServer;

    public CortexJServer(CortexJ platform, String modelReference) {
        this.platform = platform;
        this.model = platform.models().load(ModelReference.of(modelReference));
    }

    public synchronized int start(int port) throws IOException {
        if (running.get()) {
            throw new IllegalStateException("server is already running");
        }
        httpServer = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(port), 0);
        httpServer.createContext("/v1/models", exchange -> respondJson(exchange, 200,
                modelsPayload()));
        httpServer.createContext("/v1/chat/completions", exchange -> {
            try {
                handleChat(exchange);
            } catch (CortexJException failure) {
                respondError(exchange, failure.code(), failure.getMessage());
            } catch (Exception failure) {
                respondError(exchange, "CORTEXJ-SERVER-5000", String.valueOf(failure));
            }
        });
        httpServer.createContext("/v1/embeddings", exchange -> {
            try {
                handleEmbeddings(exchange);
            } catch (CortexJException failure) {
                respondError(exchange, failure.code(), failure.getMessage());
            } catch (Exception failure) {
                respondError(exchange, "CORTEXJ-SERVER-5000", String.valueOf(failure));
            }
        });
        httpServer.start();
        running.set(true);
        return httpServer.getAddress().getPort();
    }

    public synchronized void stop() {
        if (running.compareAndSet(true, false) && httpServer != null) {
            httpServer.stop(0);
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public int port() {
        return httpServer == null ? -1 : httpServer.getAddress().getPort();
    }

    private ObjectNode modelsPayload() {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("object", "list");
        ArrayNode data = payload.putArray("data");
        ObjectNode entry = data.addObject();
        entry.put("id", model.descriptor().canonicalId());
        entry.put("object", "model");
        entry.put("owned_by", "cortexj");
        return payload;
    }

    private void handleChat(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respondError(exchange, "CORTEXJ-SERVER-4000", "method not allowed");
            return;
        }
        JsonNode body = readBody(exchange);
        boolean stream = body.path("stream").asBoolean(false);
        TextGenerationCapability generator = model.capability(TextGenerationCapability.class);

        GenerationRequest.Builder request = GenerationRequest.builder();
        List<io.cortexj.model.ChatMessage> messages = chatMessages(body);
        String lastUser = "";
        for (io.cortexj.model.ChatMessage message : messages) {
            request.message(message);
            if (message.role() == io.cortexj.model.ChatRole.USER) {
                lastUser = message.content();
            }
        }
        request.prompt(lastUser);
        if (body.hasNonNull("temperature")) {
            request.temperature(body.path("temperature").asDouble());
        }
        if (body.hasNonNull("max_tokens")) {
            request.maxTokens(body.path("max_tokens").asInt());
        }

        if (stream) {
            respondStream(exchange, generator, request.build());
            return;
        }
        GenerationResponse response = generator.generate(request.build());
        ObjectNode payload = mapper.createObjectNode();
        payload.put("id", "chatcmpl-" + UUID.randomUUID().toString().substring(0, 8));
        payload.put("object", "chat.completion");
        payload.put("model", model.descriptor().canonicalId());
        ArrayNode choices = payload.putArray("choices");
        ObjectNode choice = choices.addObject();
        choice.put("index", 0);
        ObjectNode message = choice.putObject("message");
        message.put("role", "assistant");
        message.put("content", response.text());
        choice.put("finish_reason", finishReason(response.finishReason()));
        ObjectNode usage = payload.putObject("usage");
        usage.put("prompt_tokens", response.usage().isKnown() ? response.usage().inputTokens() : 0);
        usage.put("completion_tokens",
                response.usage().isKnown() ? response.usage().outputTokens() : 0);
        usage.put("total_tokens", response.usage().totalTokens());
        respondJson(exchange, 200, payload);
    }

    private void respondStream(com.sun.net.httpserver.HttpExchange exchange,
                               TextGenerationCapability generator,
                               GenerationRequest request) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        final java.util.concurrent.CountDownLatch finished =
                new java.util.concurrent.CountDownLatch(1);
        try (OutputStream output = exchange.getResponseBody()) {
            generator.stream(request).subscribe(new io.cortexj.stream.TokenConsumer() {
                @Override
                public void onToken(io.cortexj.stream.Token token) {
                    try {
                        ObjectNode chunk = mapper.createObjectNode();
                        chunk.put("id", "chatcmpl-stream");
                        chunk.put("object", "chat.completion.chunk");
                        chunk.put("model", model.descriptor().canonicalId());
                        ArrayNode choices = chunk.putArray("choices");
                        ObjectNode choice = choices.addObject();
                        choice.put("index", 0);
                        ObjectNode delta = choice.putObject("delta");
                        delta.put("content", token.text());
                        writeSse(output, chunk);
                    } catch (IOException disconnected) {
                        finished.countDown();
                    }
                }

                @Override
                public void onComplete(io.cortexj.stream.StreamCompletion completion) {
                    try {
                        output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                        output.flush();
                    } catch (IOException ignored) {
                        // client disconnected
                    } finally {
                        finished.countDown();
                    }
                }

                @Override
                public void onError(io.cortexj.error.CortexJException error) {
                    try {
                        ObjectNode failure = mapper.createObjectNode();
                        ObjectNode errorNode = failure.putObject("error");
                        errorNode.put("code", error.code());
                        errorNode.put("message", error.getMessage());
                        writeSse(output, failure);
                    } catch (IOException ignored) {
                        // client disconnected
                    } finally {
                        finished.countDown();
                    }
                }
            });
            // hold the exchange open until the stream terminates
            finished.await(10, java.util.concurrent.TimeUnit.MINUTES);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void writeSse(OutputStream output, ObjectNode chunk) throws IOException {
        output.write(("data: " + mapper.writeValueAsString(chunk) + "\n\n")
                .getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private void handleEmbeddings(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respondError(exchange, "CORTEXJ-SERVER-4000", "method not allowed");
            return;
        }
        JsonNode body = readBody(exchange);
        EmbeddingCapability embeddings = model.capability(EmbeddingCapability.class);
        java.util.List<String> inputs = new java.util.ArrayList<String>();
        JsonNode inputNode = body.path("input");
        if (inputNode.isArray()) {
            for (JsonNode item : inputNode) {
                inputs.add(item.asText());
            }
        } else {
            inputs.add(inputNode.asText(""));
        }
        EmbeddingResponse response = embeddings.embed(EmbeddingRequest.texts(inputs));
        ObjectNode payload = mapper.createObjectNode();
        payload.put("object", "list");
        payload.put("model", model.descriptor().canonicalId());
        ArrayNode data = payload.putArray("data");
        List<float[]> vectors = response.vectors();
        for (int i = 0; i < vectors.size(); i++) {
            ObjectNode entry = data.addObject();
            entry.put("object", "embedding");
            entry.put("index", i);
            ArrayNode vector = entry.putArray("embedding");
            for (float value : vectors.get(i)) {
                vector.add(value);
            }
        }
        ObjectNode usage = payload.putObject("usage");
        usage.put("prompt_tokens", 0);
        usage.put("total_tokens", 0);
        respondJson(exchange, 200, payload);
    }

    private List<io.cortexj.model.ChatMessage> chatMessages(JsonNode body) {
        java.util.List<io.cortexj.model.ChatMessage> messages =
                new java.util.ArrayList<io.cortexj.model.ChatMessage>();
        for (JsonNode message : body.path("messages")) {
            String role = message.path("role").asText("user");
            String content = message.path("content").asText("");
            io.cortexj.model.ChatRole chatRole;
            try {
                chatRole = io.cortexj.model.ChatRole.valueOf(role.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException unknownRole) {
                chatRole = io.cortexj.model.ChatRole.USER;
            }
            messages.add(chatMessage(chatRole, content));
        }
        return messages;
    }

    private static io.cortexj.model.ChatMessage chatMessage(
            io.cortexj.model.ChatRole role, String content) {
        switch (role) {
            case SYSTEM:
                return io.cortexj.model.ChatMessage.system(content);
            case ASSISTANT:
                return io.cortexj.model.ChatMessage.assistant(content);
            case TOOL:
                return io.cortexj.model.ChatMessage.of(role, content);
            default:
                return io.cortexj.model.ChatMessage.user(content);
        }
    }

    private JsonNode readBody(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        try (InputStream input = exchange.getRequestBody()) {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = input.read(chunk)) >= 0) {
                buffer.write(chunk, 0, read);
            }
            byte[] bytes = buffer.toByteArray();
            return bytes.length == 0 ? mapper.createObjectNode() : mapper.readTree(bytes);
        }
    }

    private void respondJson(com.sun.net.httpserver.HttpExchange exchange, int status,
                             ObjectNode payload) throws IOException {
        byte[] body = mapper.writeValueAsBytes(payload);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private void respondError(com.sun.net.httpserver.HttpExchange exchange, String code,
                             String message) {
        try {
            ObjectNode payload = mapper.createObjectNode();
            ObjectNode error = payload.putObject("error");
            error.put("code", code);
            error.put("message", message == null ? "unknown error" : message);
            respondJson(exchange, 500, payload);
        } catch (IOException ignored) {
            // client gone
        }
    }

    private static String finishReason(io.cortexj.inference.FinishReason reason) {
        if (reason == null) {
            return "stop";
        }
        switch (reason) {
            case LENGTH:
                return "length";
            case CONTENT_FILTER:
                return "content_filter";
            case TOOL_CALL:
                return "tool_calls";
            default:
                return "stop";
        }
    }
}
