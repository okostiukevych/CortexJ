package io.cortexj.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tool-calling surface (audit 2026-10-21 tracer): the model can propose a
 * tool call, the caller executes it and feeds the result back as a TOOL
 * message. The value types are the contract carriers for runtimes that do
 * parse structured tool calls (remote OpenAI) and for the adapters that
 * hand them to agentic frameworks.
 */
public class ToolCallSurfaceTest {

    @Test
    public void toolCallCarriesIdNameAndRawArguments() {
        ToolCall call = new ToolCall("call_1", "get_weather", "{\"city\":\"Omsk\"}");
        assertEquals("call_1", call.id());
        assertEquals("get_weather", call.name());
        assertEquals("{\"city\":\"Omsk\"}", call.arguments());
    }

    @Test
    public void toolCallDefaultsNullIdAndArgumentsToEmpty() {
        // some providers do not assign stable ids and stream arguments
        // incrementally: "" is the honest unknown
        ToolCall call = new ToolCall(null, "get_weather", null);
        assertEquals("", call.id());
        assertEquals("get_weather", call.name());
        assertEquals("", call.arguments());
    }

    @Test
    public void toolCallRejectsBlankName() {
        assertThrows(IllegalArgumentException.class, () -> new ToolCall("id", "  ", "{}"));
        assertThrows(IllegalArgumentException.class, () -> new ToolCall("id", null, "{}"));
    }

    @Test
    public void toolDefinitionCarriesNameDescriptionAndJsonSchema() {
        ToolDefinition tool = ToolDefinition.function("get_weather",
                "Read the current weather for a city", "{\"type\":\"object\"}");
        assertEquals("get_weather", tool.name());
        assertEquals("Read the current weather for a city", tool.description());
        assertEquals("{\"type\":\"object\"}", tool.parametersJson());
    }

    @Test
    public void toolDefinitionToleratesMissingSchemaAndDescription() {
        // providers treat an absent parameters schema as "no arguments"; the
        // empty string stays the honest carrier of that fact
        ToolDefinition tool = ToolDefinition.function("no_args", null, null);
        assertEquals("no_args", tool.name());
        assertEquals("", tool.description());
        assertEquals("", tool.parametersJson());
    }

    @Test
    public void toolDefinitionRejectsBlankName() {
        assertThrows(IllegalArgumentException.class, () -> ToolDefinition.function("", "d", "{}"));
    }

    @Test
    public void toolMessageCarriesTheResultAndTheCallId() {
        ChatMessage result = ChatMessage.tool("call_1", "{\"temp\":-3}");
        assertSame(ChatRole.TOOL, result.role());
        assertEquals("call_1", result.toolCallId());
        assertEquals("{\"temp\":-3}", result.content());
    }

    @Test
    public void assistantMessageCanEchoItsToolCalls() {
        // the loop controller must echo the proposed calls back in the next
        // round along with the executed results
        ChatMessage echo = ChatMessage.assistantToolCalls(
                Collections.singletonList(new ToolCall("call_1", "get_weather", "{}")), "");
        assertSame(ChatRole.ASSISTANT, echo.role());
        assertEquals(1, echo.toolCalls().size());
        assertEquals("get_weather", echo.toolCalls().get(0).name());
        assertEquals("", echo.content());
    }

    @Test
    public void legacyFactoriesCarryNoToolState() {
        ChatMessage plain = ChatMessage.user("hi");
        assertEquals("", plain.toolCallId());
        assertTrue(plain.toolCalls().isEmpty());
    }

    @Test
    public void toolMessageToolRoleRejectedCallIdBlank() {
        assertThrows(IllegalArgumentException.class, () -> ChatMessage.tool("  ", "{}"));
    }

    @Test
    public void generationResponseCarriesProposedToolCallsWhenTheModelCallsForThem() {
        io.cortexj.inference.GenerationResponse response =
                new io.cortexj.inference.GenerationResponse("", io.cortexj.inference.FinishReason.TOOL_CALL,
                        null, null, Collections.singletonList(new ToolCall("call_1", "get_weather", "{}")));
        assertEquals(1, response.toolCalls().size());
        assertEquals("call_1", response.toolCalls().get(0).id());
    }

    @Test
    public void textOnlyGenerationResponseDeclaresNoToolCalls() {
        io.cortexj.inference.GenerationResponse response =
                new io.cortexj.inference.GenerationResponse("hello", null, null, null);
        assertTrue(response.toolCalls().isEmpty());
    }

    @Test
    public void generationRequestCarriesDeclaredTools() {
        ToolDefinition getWeather = ToolDefinition.function("get_weather", null, "{}");
        io.cortexj.inference.GenerationRequest request =
                io.cortexj.inference.GenerationRequest.builder()
                        .message(ChatMessage.user("weather?"))
                        .tools(Arrays.asList(getWeather, ToolDefinition.function("get_time", "", "")))
                        .build();
        assertEquals(2, request.tools().size());
        assertEquals("get_weather", request.tools().get(0).name());
    }

    @Test
    public void generationRequestDefaultsToNoDeclaredTools() {
        io.cortexj.inference.GenerationRequest request = io.cortexj.inference.GenerationRequest.prompt("hi");
        assertTrue(request.tools().isEmpty());
        // request stays message-only when no tools were declared
        assertTrue(request.messages().isEmpty());
        assertEquals("hi", request.prompt());
    }
}
