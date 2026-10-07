package io.cortexj.server;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Optional embedded server mode (spec 60): exposes an OpenAI-compatible local
 * endpoint on top of a selected runtime.
 */
public final class CortexJServer {

    private static final long DEFAULT_STREAM_AWAIT_MILLIS = 1_800_000L;
    private static final long DEFAULT_MAX_BODY_BYTES = 8L * 1024L * 1024L;
    private static final int SATURATION_HEADROOM_THREADS = 4;
    private static final int MIN_QUEUED_REQUESTS = 64;
    private static final String GENERIC_ERROR_MESSAGE = "internal error, see server logs";

    private final CortexJ platform;
    private final ModelHandle model;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final long streamAwaitMillis;
    private final long maxBodyBytes;
    private final String authToken;
    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(CortexJServer.class.getName());

    private volatile com.sun.net.httpserver.HttpServer httpServer;
    private ThreadPoolExecutor requestExecutor;
    private Semaphore admission;

    public CortexJServer(CortexJ platform, String modelReference) {
        this(platform, modelReference, 0L);
    }

    public CortexJServer(CortexJ platform, String modelReference, long streamAwaitMillis) {
        this(platform, modelReference, streamAwaitMillis, defaultAuthToken());
    }

    /**
     * @param streamAwaitMillis explicit ceiling for holding an SSE exchange open;
     *                          when {@code <= 0} the system property
     *                          {@code cortexj.server.streamAwaitMillis} applies, then the
     *                          30-minute default. On timeout the upstream generation is
     *                          cancelled and an SSE error frame is emitted before close.
     * @param authToken optional bearer token enforced on every /v1/* route;
     *                  when {@code null} no auth is required (loopback-only
     *                  bind remains the network guard)
     */
    public CortexJServer(CortexJ platform, String modelReference, long streamAwaitMillis,
                         String authToken) {
        this.platform = platform;
        this.model = platform.models().load(ModelReference.of(modelReference));
        this.streamAwaitMillis = resolveStreamAwaitMillis(streamAwaitMillis);
        this.maxBodyBytes = positiveLongProperty("cortexj.server.maxBodyBytes", DEFAULT_MAX_BODY_BYTES);
        this.authToken = authToken == null || authToken.trim().isEmpty() ? null : authToken.trim();
    }

    private static long resolveStreamAwaitMillis(long explicit) {
        if (explicit > 0) {
            return explicit;
        }
        return positiveLongProperty("cortexj.server.streamAwaitMillis", DEFAULT_STREAM_AWAIT_MILLIS);
    }

    private static long positiveLongProperty(String name, long fallback) {
        String value = System.getProperty(name);
        if (value != null && !value.trim().isEmpty()) {
            try {
                long parsed = Long.parseLong(value.trim());
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // fall back to the default
            }
        }
        return fallback;
    }

    private static String defaultAuthToken() {
        String env = System.getenv("CORTEXJ_SERVER_AUTH");
        if (env != null && !env.trim().isEmpty()) {
            return env.trim();
        }
        String property = System.getProperty("cortexj.server.auth");
        return property == null || property.trim().isEmpty() ? null : property.trim();
    }

    public synchronized int start(int port) throws IOException {
        if (running.get()) {
            throw new IllegalStateException("server is already running");
        }
        // security default (audit H-10): loopback only; listening on all
        // interfaces requires an explicit opt-in via -Dcortexj.server.bind=...
        String bindAddress = System.getProperty("cortexj.server.bind");
        InetAddress bindHost = bindAddress != null && !bindAddress.trim().isEmpty()
                ? InetAddress.getByName(bindAddress.trim())
                : InetAddress.getLoopbackAddress();
        // audit (GitHub #82): a non-loopback bind without auth exposes an
        // unauthenticated LLM endpoint on the network; require a token or an
        // explicit insecure acknowledgment
        if (!bindHost.isLoopbackAddress() && authToken == null && !insecureBindAllowed()) {
            throw new CortexJException("CORTEXJ-SERVER-4020",
                    "refusing to bind a non-loopback address (" + bindHost.getHostAddress()
                            + ") without authentication: this exposes an unauthenticated "
                            + "LLM endpoint on the network",
                    "set a bearer token (CORTEXJ_SERVER_AUTH or -Dcortexj.server.auth), or "
                            + "acknowledge the risk explicitly with "
                            + "-Dcortexj.server.insecureBind=true");
        }
        httpServer = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(bindHost, port), 0);
        int workers = Math.max(8, 2 * Runtime.getRuntime().availableProcessors());
        requestExecutor = new ThreadPoolExecutor(
                workers + SATURATION_HEADROOM_THREADS, workers + SATURATION_HEADROOM_THREADS,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(Math.max(MIN_QUEUED_REQUESTS, workers)),
                io.cortexj.spi.platform.PlatformModule.daemonThreadFactory("cortexj-server"),
                new ThreadPoolExecutor.AbortPolicy());
        httpServer.setExecutor(requestExecutor);
        admission = new Semaphore(workers);
        httpServer.createContext("/v1/models", exchange ->
                dispatch(exchange, (ctx, headersSent) -> {
                    if (!authorized(ctx, headersSent)) {
                        return;
                    }
                    respondJson(ctx, headersSent, 200, modelsPayload());
                }));
        httpServer.createContext("/v1/chat/completions", exchange ->
                dispatch(exchange, (ctx, headersSent) -> {
                    if (!authorized(ctx, headersSent)) {
                        return;
                    }
                    handleChat(ctx, headersSent);
                }));
        httpServer.createContext("/v1/embeddings", exchange ->
                dispatch(exchange, (ctx, headersSent) -> {
                    if (!authorized(ctx, headersSent)) {
                        return;
                    }
                    handleEmbeddings(ctx, headersSent);
                }));
        httpServer.createContext("/", exchange ->
                dispatch(exchange, (ctx, headersSent) ->
                        respondError(ctx, headersSent, 404, "CORTEXJ-SERVER-4040", "unknown path")));
        httpServer.start();
        running.set(true);
        return httpServer.getAddress().getPort();
    }

    public synchronized void stop() {
        if (running.compareAndSet(true, false) && httpServer != null) {
            httpServer.stop(0);
        }
        if (requestExecutor != null) {
            requestExecutor.shutdownNow();
            requestExecutor = null;
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public int port() {
        return httpServer == null ? -1 : httpServer.getAddress().getPort();
    }

    public java.net.InetAddress boundAddress() {
        return httpServer == null ? null : httpServer.getAddress().getAddress();
    }

    private static boolean insecureBindAllowed() {
        return Boolean.parseBoolean(System.getProperty("cortexj.server.insecureBind"));
    }

    private void dispatch(com.sun.net.httpserver.HttpExchange exchange, ExchangeHandler handler) {
        if (admission == null || !admission.tryAcquire()) {
            respondServiceUnavailable(exchange);
            return;
        }
        AtomicBoolean headersSent = new AtomicBoolean(false);
        try {
            handler.handle(exchange, headersSent);
        } catch (CortexJException failure) {
            LOG.log(java.util.logging.Level.SEVERE,
                    "request failed: " + failure.code(), failure);
            respondError(exchange, headersSent, 500, failure.code(), GENERIC_ERROR_MESSAGE);
        } catch (Exception failure) {
            LOG.log(java.util.logging.Level.SEVERE, "request failed", failure);
            respondError(exchange, headersSent, 500, "CORTEXJ-SERVER-5000", GENERIC_ERROR_MESSAGE);
        } finally {
            admission.release();
        }
    }

    private interface ExchangeHandler {
        void handle(com.sun.net.httpserver.HttpExchange exchange,
                    AtomicBoolean headersSent) throws IOException;
    }

    private boolean authorized(com.sun.net.httpserver.HttpExchange exchange,
                               AtomicBoolean headersSent) throws IOException {
        if (authToken == null) {
            // audit (GitHub #82): with no auth token the loopback bind is the only
            // guard, so a Host header pointing elsewhere (CSRF/DNS rebinding) must
            // be rejected
            if (!isLoopbackHost(exchange.getRequestHeaders().getFirst("Host"))) {
                respondError(exchange, headersSent, 403, "CORTEXJ-SERVER-4030",
                        "forbidden: unauthenticated access is restricted to loopback hosts");
                return false;
            }
            return true;
        }
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header != null && header.startsWith("Bearer ")
                && constantTimeEquals(header.substring(7).trim(), authToken)) {
            return true;
        }
        exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer realm=\"cortexj\"");
        respondJson(exchange, headersSent, 401, errorPayload("CORTEXJ-SERVER-4010",
                "unauthorized: a valid bearer token is required"));
        return false;
    }

    static boolean isLoopbackHost(String hostHeader) {
        if (hostHeader == null) {
            return false;
        }
        String host = hostHeader.trim();
        if (host.isEmpty()) {
            return false;
        }
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            if (end < 0) {
                return false;
            }
            host = host.substring(1, end);
        } else {
            // a single ':' means host:port; several colons mean a bare IPv6
            // literal like "::1" or "0:0:0:0:0:0:0:1" (no port stripping)
            int colons = 0;
            for (int i = 0; i < host.length(); i++) {
                if (host.charAt(i) == ':') {
                    colons++;
                }
            }
            if (colons == 1) {
                int portSeparator = host.lastIndexOf(':');
                host = host.substring(0, portSeparator);
            }
        }
        if (host.isEmpty()) {
            return false;
        }
        // audit #90: no DNS resolution of the attacker-controlled Host header.
        // Resolving it let a rebinding domain with an A record pointing at
        // 127.0.0.1 pass the guard; only literal loopback names are accepted.
        if ("localhost".equalsIgnoreCase(host)) {
            return true;
        }
        if (isLiteralIpv4(host)) {
            return host.startsWith("127.");
        }
        return "::1".equals(host) || "0:0:0:0:0:0:0:1".equalsIgnoreCase(host);
    }

    private static boolean isLiteralIpv4(String host) {
        String[] octets = host.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3) {
                return false;
            }
            for (int i = 0; i < octet.length(); i++) {
                char c = octet.charAt(i);
                if (c < '0' || c > '9') {
                    return false;
                }
            }
            if (Integer.parseInt(octet) > 255) {
                return false;
            }
        }
        return true;
    }

    private static boolean constantTimeEquals(String left, String right) {
        return java.security.MessageDigest.isEqual(
                left.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                right.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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

    private void handleChat(com.sun.net.httpserver.HttpExchange exchange,
                            AtomicBoolean headersSent) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().add("Allow", "POST");
            respondError(exchange, headersSent, 405, "CORTEXJ-SERVER-4050", "method not allowed");
            return;
        }
        JsonNode body = readBody(exchange, headersSent);
        if (body == null) {
            // an error response was already sent by readBody
            return;
        }
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
            respondStream(exchange, headersSent, generator, request.build());
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
        respondJson(exchange, headersSent, 200, payload);
    }

    private void respondStream(com.sun.net.httpserver.HttpExchange exchange,
                               AtomicBoolean headersSent,
                               TextGenerationCapability generator,
                               GenerationRequest request) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        headersSent.set(true);
        final java.util.concurrent.CountDownLatch finished =
                new java.util.concurrent.CountDownLatch(1);
        final AtomicBoolean clientGone = new AtomicBoolean(false);
        final AtomicBoolean cancelledUpstream = new AtomicBoolean(false);
        final AtomicReference<io.cortexj.stream.StreamSubscription> subscription =
                new AtomicReference<io.cortexj.stream.StreamSubscription>();
        // (GitHub #58): the subscription is retained so the disconnect, timeout
        // and interrupt cleanup paths can cancel the upstream generation
        try (OutputStream output = exchange.getResponseBody()) {
            try {
                subscription.set(generator.stream(request).subscribe(new io.cortexj.stream.TokenConsumer() {
                    @Override
                    public void onToken(io.cortexj.stream.Token token) {
                        if (clientGone.get() || cancelledUpstream.get()) {
                            return;
                        }
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
                            clientGone.set(true);
                            finished.countDown();
                            cancelSubscription(subscription.get());
                        }
                    }

                    @Override
                    public void onComplete(io.cortexj.stream.StreamCompletion completion) {
                        try {
                            synchronized (output) {
                                output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                                output.flush();
                            }
                        } catch (IOException ignored) {
                            // client disconnected
                        } finally {
                            finished.countDown();
                        }
                    }

                    @Override
                    public void onError(io.cortexj.error.CortexJException error) {
                        try {
                            writeSseError(output, error.code(), error.getMessage());
                        } catch (IOException ignored) {
                            // client disconnected
                        } finally {
                            finished.countDown();
                        }
                    }
                }));
            } catch (CortexJException error) {
                writeSseErrorQuietly(output, error.code(), error.getMessage());
                return;
            } catch (RuntimeException failure) {
                LOG.log(java.util.logging.Level.SEVERE, "stream subscription failed", failure);
                writeSseErrorQuietly(output, "CORTEXJ-SERVER-5000", GENERIC_ERROR_MESSAGE);
                return;
            }
            if (clientGone.get()) {
                cancelSubscription(subscription.get());
                return;
            }
            // hold the exchange open until the stream terminates
            boolean terminated = finished.await(streamAwaitMillis, TimeUnit.MILLISECONDS);
            if (!terminated) {
                LOG.log(java.util.logging.Level.WARNING,
                        "stream hold timed out after " + streamAwaitMillis
                                + "ms; the upstream generation is cancelled and the exchange is closed");
                cancelledUpstream.set(true);
                cancelSubscription(subscription.get());
                writeSseErrorQuietly(output, "CORTEXJ-SERVER-5040",
                        "stream timed out after " + streamAwaitMillis
                                + "ms; the generation was cancelled");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            cancelledUpstream.set(true);
            cancelSubscription(subscription.get());
        }
    }

    private void cancelSubscription(io.cortexj.stream.StreamSubscription subscription) {
        if (subscription == null) {
            return;
        }
        try {
            subscription.cancel();
        } catch (RuntimeException failure) {
            LOG.log(java.util.logging.Level.FINE, "stream cancel failed", failure);
        }
    }

    private void writeSse(OutputStream output, ObjectNode chunk) throws IOException {
        // audit #117: the runtime worker and the timeout path can write frames
        // concurrently - synchronize on the output itself so frame bytes never
        // interleave
        synchronized (output) {
            output.write(("data: " + mapper.writeValueAsString(chunk) + "\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }

    private void writeSseError(OutputStream output, String code, String message) throws IOException {
        writeSse(output, errorPayload(code, message));
    }

    private void writeSseErrorQuietly(OutputStream output, String code, String message) {
        try {
            writeSseError(output, code, message);
        } catch (IOException ignored) {
            // client disconnected
        }
    }

    private void handleEmbeddings(com.sun.net.httpserver.HttpExchange exchange,
                                  AtomicBoolean headersSent) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().add("Allow", "POST");
            respondError(exchange, headersSent, 405, "CORTEXJ-SERVER-4050", "method not allowed");
            return;
        }
        JsonNode body = readBody(exchange, headersSent);
        if (body == null) {
            // an error response was already sent by readBody
            return;
        }
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
        respondJson(exchange, headersSent, 200, payload);
    }

    private List<io.cortexj.model.ChatMessage> chatMessages(JsonNode body) {
        java.util.List<io.cortexj.model.ChatMessage> messages =
                new java.util.ArrayList<io.cortexj.model.ChatMessage>();
        for (JsonNode message : body.path("messages")) {
            String role = message.path("role").asText("user");
            String content = message.path("content").asText("");
            io.cortexj.model.ChatRole chatRole;
            try {
                chatRole = io.cortexj.model.ChatRole.valueOf(role.toUpperCase(Locale.ROOT));
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

    private JsonNode readBody(com.sun.net.httpserver.HttpExchange exchange,
                              AtomicBoolean headersSent) throws IOException {
        // audit H-11: cap the request body - a multi-GB POST (chunked or with a
        // huge Content-Length) must not OOM the process; JSON bodies only
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null
                || !contentType.trim().toLowerCase(Locale.ROOT).startsWith("application/json")) {
            respondError(exchange, headersSent, 415, "CORTEXJ-SERVER-4150",
                    "unsupported media type: Content-Type must be application/json");
            return null;
        }
        String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
        if (contentLength != null) {
            try {
                if (Long.parseLong(contentLength.trim()) > maxBodyBytes) {
                    rejectTooLarge(exchange, headersSent);
                    return null;
                }
            } catch (NumberFormatException ignored) {
                // fall through to the streaming cap
            }
        }
        try (InputStream input = exchange.getRequestBody()) {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            long total = 0;
            int read;
            while ((read = input.read(chunk)) >= 0) {
                total += read;
                if (total > maxBodyBytes) {
                    rejectTooLarge(exchange, headersSent);
                    return null;
                }
                buffer.write(chunk, 0, read);
            }
            byte[] bytes = buffer.toByteArray();
            return bytes.length == 0 ? mapper.createObjectNode() : mapper.readTree(bytes);
        } catch (JsonProcessingException malformed) {
            respondError(exchange, headersSent, 400, "CORTEXJ-SERVER-4000",
                    "malformed JSON request body");
            return null;
        }
    }

    private void rejectTooLarge(com.sun.net.httpserver.HttpExchange exchange,
                                AtomicBoolean headersSent) {
        try {
            respondJson(exchange, headersSent, 413, errorPayload("CORTEXJ-SERVER-4130",
                    "request body too large (max " + maxBodyBytes + " bytes)"));
        } catch (IOException ignored) {
            // client gone
        }
    }

    private void respondJson(com.sun.net.httpserver.HttpExchange exchange,
                             AtomicBoolean headersSent, int status,
                             ObjectNode payload) throws IOException {
        byte[] body = mapper.writeValueAsBytes(payload);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        headersSent.set(true);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        } catch (IOException clientGone) {
            // headers are already on the wire; a failure here means the client
            // went away mid-response and there is nothing left to report
        }
    }

    private ObjectNode errorPayload(String code, String message) {
        ObjectNode payload = mapper.createObjectNode();
        ObjectNode error = payload.putObject("error");
        error.put("code", code);
        error.put("message", message == null ? "unknown error" : message);
        return payload;
    }

    private void respondError(com.sun.net.httpserver.HttpExchange exchange,
                              AtomicBoolean headersSent, int status, String code,
                              String message) {
        if (headersSent.get()) {
            // headers are already on the wire: a second sendResponseHeaders would
            // throw IllegalStateException, so just close the exchange
            exchange.close();
            return;
        }
        try {
            respondJson(exchange, headersSent, status, errorPayload(code, message));
        } catch (IOException ignored) {
            // client gone
        }
    }

    private void respondServiceUnavailable(com.sun.net.httpserver.HttpExchange exchange) {
        try {
            exchange.getResponseHeaders().add("Retry-After", "1");
            respondJson(exchange, new AtomicBoolean(false), 503,
                    errorPayload("CORTEXJ-SERVER-5030",
                            "server busy: too many in-flight requests"));
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
