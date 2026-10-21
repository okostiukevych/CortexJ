package io.cortexj.stream;

import io.cortexj.inference.FinishReason;
import io.cortexj.model.ToolCall;
import io.cortexj.stream.internal.TokenStreamPublisher;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tool-call dispatch over the push stream (audit 2026-10-21 tracer): a
 * provider that surfaces structured tool calls publishes them as a batch and
 * the consumer receives every batch before the terminal - the agentic caller
 * has the calls it needs at the moment it sees finishReason TOOL_CALL.
 */
public class ToolCallDispatchTest {

    @Test
    public void toolCallBatchesAreDeliveredAfterTheLastTokenAndBeforeCompletion() {
        TokenStreamPublisher publisher = new TokenStreamPublisher();
        final List<String> order = new ArrayList<String>();
        TokenConsumer consumer = new TokenConsumer() {
            @Override
            public void onToken(Token token) {
                order.add("token:" + token.text());
            }

            @Override
            public void onToolCalls(List<ToolCall> toolCalls) {
                order.add("toolCalls:" + toolCalls.size());
            }

            @Override
            public void onComplete(StreamCompletion completion) {
                order.add("complete:" + completion.finishReason());
            }

            @Override
            public void onError(io.cortexj.error.CortexJException error) {
                order.add("error");
            }
        };

        TokenStream stream = publisher.stream();
        stream.subscribe(consumer);
        publisher.publish("thinking:");
        publisher.publishToolCalls(Collections.singletonList(new ToolCall("call_1", "get_weather", "{}")));
        publisher.complete(FinishReason.TOOL_CALL, 9, 4);

        assertEquals(Arrays.asList("token:thinking:", "toolCalls:1", "complete:TOOL_CALL"), order);
    }

    @Test
    public void plainTextStreamsNeverDeliverToolCallBatches() {
        TokenStreamPublisher publisher = new TokenStreamPublisher();
        final List<Integer> deliveries = new ArrayList<Integer>();
        TokenConsumer consumer = new TokenConsumer() {
            @Override
            public void onToken(Token token) {
            }

            @Override
            public void onToolCalls(List<ToolCall> toolCalls) {
                deliveries.add(toolCalls.size());
            }

            @Override
            public void onComplete(StreamCompletion completion) {
            }

            @Override
            public void onError(io.cortexj.error.CortexJException error) {
            }
        };

        TokenStream stream = publisher.stream();
        stream.subscribe(consumer);
        publisher.publish("hello");
        publisher.complete(FinishReason.STOP, -1, -1);

        assertTrue(deliveries.isEmpty());
    }

    @Test
    public void toolCallBatchPublishedBeforeSubscribeIsReplayedInOrder() {
        TokenStreamPublisher publisher = new TokenStreamPublisher();
        final List<String> order = new ArrayList<String>();
        TokenConsumer consumer = new TokenConsumer() {
            @Override
            public void onToken(Token token) {
                order.add("token");
            }

            @Override
            public void onToolCalls(List<ToolCall> toolCalls) {
                order.add("toolCalls:" + toolCalls.size());
            }

            @Override
            public void onComplete(StreamCompletion completion) {
                order.add("complete:" + completion.finishReason());
            }

            @Override
            public void onError(io.cortexj.error.CortexJException error) {
                order.add("error");
            }
        };

        publisher.publish("buffered answer");
        publisher.publishToolCalls(Arrays.asList(
                new ToolCall("call_1", "get_weather", "{}"),
                new ToolCall("call_2", "get_time", "")));
        publisher.complete(FinishReason.TOOL_CALL, -1, -1);

        TokenStream stream = publisher.stream();
        stream.subscribe(consumer);

        assertEquals(Arrays.asList("token", "toolCalls:2", "complete:TOOL_CALL"), order);
    }
}
