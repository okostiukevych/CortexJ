package io.cortexj.runtime.remote;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.cortexj.inference.GenerationRequest;
import io.cortexj.inference.FinishReason;
import io.cortexj.model.ChatMessage;
import io.cortexj.model.ToolDefinition;
import io.cortexj.spi.runtime.ModelRuntime;
import io.cortexj.spi.runtime.RuntimeModel;
import io.cortexj.spi.runtime.RuntimeModelRequest;
import io.cortexj.stream.TokenConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire contract of the agentic loop through the OpenAI-compatible remote
 * runtime (audit 2026-10-21 tracer): declared tools ride the request
 * payload, proposed calls ride the response - both directions verbatim.
 */
class RemoteToolCallTest {

    private HttpServer server;
    private String baseUrl;
    private final List<String> requestBodies = new ArrayList<String>();
    private String nonStreamResponse;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        server.createContext("/v1/chat/completions", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                String captured = readBody(exchange);
                requestBodies.add(captured);
                if (nonStreamResponse == null) {
                    nonStreamResponse = "{\"choices\":[{\"finish_reason\":\"tool_calls\","
                            + "\"message\":{\"content\":\"\",\"tool_calls\":["
                            + "{\"id\":\"call_abc\",\"type\":\"function\","
                            + "\"function\":{\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"Omsk\\\"}\"}}]}}],"
                            + "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":5}}";
                }
                respondJson(exchange, nonStreamResponse);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        InputStream input = exchange.getRequestBody();
        StringBuilder body = new StringBuilder();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            body.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
        }
        input.close();
        return body.toString();
    }

    private static void respondJson(HttpExchange exchange, String json) throws IOException {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private RuntimeModel loadModel() {
        RemoteOpenAiProvider provider = new RemoteOpenAiProvider();
        ModelRuntime runtime = provider.open(new io.cortexj.spi.runtime.RuntimeOpenRequest(
                io.cortexj.model.ModelDescriptor.builder("tck/model")
                        .task(io.cortexj.model.TaskCapability.TEXT_GENERATION).build(),
                new io.cortexj.model.ModelArtifact() {
                    @Override
                    public String id() {
                        return "remote";
                    }

                    @Override
                    public io.cortexj.model.ArtifactFormat format() {
                        return io.cortexj.model.ArtifactFormat.REMOTE;
                    }

                    @Override
                    public long sizeBytes() {
                        return 0;
                    }

                    @Override
                    public io.cortexj.model.Checksum checksum() {
                        return null;
                    }

                    @Override
                    public java.util.Map<String, String> metadata() {
                        return java.util.Collections.singletonMap("remoteModel", "test-model");
                    }
                },
                null,
                io.cortexj.runtime.DeviceType.REMOTE,
                java.util.Collections.<String, Object>emptyMap(),
                java.util.Collections.singletonMap("remote.baseUrl", baseUrl),
                null, null, null, null));
        return runtime.load(new RuntimeModelRequest(
                io.cortexj.model.ModelDescriptor.builder("tck/model").build(),
                null, null, io.cortexj.runtime.DeviceType.REMOTE,
                java.util.Collections.<String, Object>emptyMap()));
    }

    private static boolean contains(String haystack, String needle) {
        return haystack.replace(" ", "").replace("\n", "").contains(needle.replace(" ", ""));
    }

    @Test
    void declaredToolsRideTheRequestPayload() {
        RuntimeModel model = loadModel();
        try {
            io.cortexj.inference.TextGenerationCapability generator =
                    (io.cortexj.inference.TextGenerationCapability) model;
            generator.generate(GenerationRequest.builder()
                    .prompt("weather?")
                    .tool(ToolDefinition.function("get_weather", "Read the weather",
                            "{\"type\":\"object\"}"))
                    .build());

            assertTrue(contains(requestBodies.get(0),
                    "\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"get_weather\","
                            + "\"description\":\"Read the weather\",\"parameters\":{\"type\":\"object\"}}}]"));
        } finally {
            model.close();
        }
    }

    @Test
    void proposedToolCallsRideTheResponseWithTheToolCallFinishReason() {
        RuntimeModel model = loadModel();
        try {
            io.cortexj.inference.TextGenerationCapability generator =
                    (io.cortexj.inference.TextGenerationCapability) model;
            io.cortexj.inference.GenerationResponse response = generator.generate(
                    GenerationRequest.prompt("weather?"));

            assertEquals(FinishReason.TOOL_CALL, response.finishReason());
            assertEquals(1, response.toolCalls().size());
            assertEquals("call_abc", response.toolCalls().get(0).id());
            assertEquals("get_weather", response.toolCalls().get(0).name());
            assertEquals("{\"city\":\"Omsk\"}", response.toolCalls().get(0).arguments());
        } finally {
            model.close();
        }
    }

    @Test
    void loopBackMessagesCarryTheirWireRoles() {
        RuntimeModel model = loadModel();
        try {
            io.cortexj.inference.TextGenerationCapability generator =
                    (io.cortexj.inference.TextGenerationCapability) model;
            generator.generate(GenerationRequest.builder()
                    .message(ChatMessage.user("weather?"))
                    .message(ChatMessage.assistantToolCalls(Collections.singletonList(
                            new io.cortexj.model.ToolCall("call_abc", "get_weather",
                                    "{\"city\":\"Omsk\"}")), ""))
                    .message(ChatMessage.tool("call_abc", "{\"temp\":-3}"))
                    .build());

            String payload = requestBodies.get(0);
            // assistant echo carries the proposed calls
            assertTrue(contains(payload, "\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":["
                    + "{\"id\":\"call_abc\",\"type\":\"function\",\"function\":"
                    + "{\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"Omsk\\\"}\"}}]"));
            // the executed result is tied to its call by id
            assertTrue(contains(payload, "\"role\":\"tool\",\"content\":\"{\\\"temp\\\":-3}\","
                    + "\"tool_call_id\":\"call_abc\""));
        } finally {
            model.close();
        }
    }

    @Test
    void streamedToolCallFragmentsAccumulateIntoCompleteCalls() throws Exception {
        server.stop(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        server.createContext("/v1/chat/completions", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                requestBodies.add(readBody(exchange));
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    String[] chunks = new String[]{
                            "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                                    + "\"id\":\"call_st1\",\"type\":\"function\","
                                    + "\"function\":{\"name\":\"get_weather\",\"arguments\":\"{\"}}]}}]}",
                            "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                                    + "\"function\":{\"arguments\":\"\\\"city\\\":\\\"Omsk\\\"}\"}}]}}]}",
                            "{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}",
                            "{\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3}," 
                                    + "\"choices\":[]}"};
                    for (String chunk : chunks) {
                        out.write(("data: " + chunk + "\n\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                    out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        RuntimeModel model = loadModel();
        try {
            io.cortexj.inference.TextGenerationCapability generator =
                    (io.cortexj.inference.TextGenerationCapability) model;
            final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            final List<io.cortexj.model.ToolCall> received = new ArrayList<>();
            final FinishReason[] finish = {null};

            generator.stream(GenerationRequest.builder()
                    .prompt("weather?")
                    .tool(ToolDefinition.function("get_weather", "", ""))
                    .build())
                    .subscribe(new TokenConsumer() {
                        @Override
                        public void onToken(io.cortexj.stream.Token token) {

                        }

                        @Override
                        public void onToolCalls(List<io.cortexj.model.ToolCall> toolCalls) {

                            received.addAll(toolCalls);
                        }

                        @Override
                        public void onComplete(io.cortexj.stream.StreamCompletion completion) {

                            finish[0] = completion.finishReason();
                            done.countDown();
                        }

                        @Override
                        public void onError(io.cortexj.error.CortexJException error) {

                            done.countDown();
                        }
                    });
            assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS), "stream did not finish");
            assertEquals(1, received.size());
            assertEquals("call_st1", received.get(0).id());
            assertEquals("get_weather", received.get(0).name());
            // the arguments streamed as three fragments assemble verbatim
            assertEquals("{\"city\":\"Omsk\"}", received.get(0).arguments());
            assertEquals(FinishReason.TOOL_CALL, finish[0]);
        } finally {
            model.close();
        }
    }

    @Test
    void malformedToolSchemaFailsTypedNamingTheTool() {
        // the schema travels as a JSON document: a caller typo must surface as
        // a typed CORTEXJ- error naming the tool and the field to fix, never
        // as a response-flavored untyped failure
        RuntimeModel model = loadModel();
        try {
            io.cortexj.inference.TextGenerationCapability generator =
                    (io.cortexj.inference.TextGenerationCapability) model;
            io.cortexj.error.CortexJException failure = org.junit.jupiter.api.Assertions.assertThrows(
                    io.cortexj.error.CortexJException.class,
                    () -> generator.generate(GenerationRequest.builder()
                            .prompt("weather?")
                            .tool(ToolDefinition.function("get_weather", "", "{not a schema"))
                            .build()));
            assertTrue(failure.code().startsWith("CORTEXJ-"),
                    "the failure must carry a CORTEXJ- code: " + failure.code());
            assertTrue(failure.getMessage().contains("get_weather"),
                    "the message must name the offending tool: " + failure.getMessage());
            assertTrue(failure.getMessage().contains("schema"),
                    "the message must say what is wrong: " + failure.getMessage());
            assertTrue(failure.remediation() != null && !failure.remediation().isEmpty());
        } finally {
            model.close();
        }
    }
}
