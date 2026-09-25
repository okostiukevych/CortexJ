# LangChain4j Integration

The `cortexj-langchain4j` adapter exposes a loaded CortexJ model as LangChain4j 1.0.x
`ChatModel`, `StreamingChatModel`, and `EmbeddingModel`. Requires Java 17+.

## Dependency

```xml
<dependency>
    <groupId>io.cortexj</groupId>
    <artifactId>cortexj-langchain4j</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## Chat

```java
import io.cortexj.langchain4j.CortexJChatModel;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;

try (CortexJChatModel chat = new CortexJChatModel("Qwen/Qwen3-8B")) {
    ChatResponse response = chat.chat(
            ChatRequest.builder().messages(UserMessage.from("hi")).build());
    System.out.println(response.aiMessage().text());
    System.out.println(response.metadata().tokenUsage().inputTokenCount());
}
```

Token usage and finish reasons are mapped to LangChain4j types (`TokenUsage`,
`FinishReason`).

## Streaming

```java
try (CortexJStreamingChatModel chat = new CortexJStreamingChatModel("Qwen/Qwen3-8B")) {
    chat.chat(ChatRequest.builder().messages(UserMessage.from("hi")).build(),
            new StreamingChatResponseHandler() {
                @Override public void onPartialResponse(String token) { System.out.print(token); }
                @Override public void onCompleteResponse(ChatResponse r) { }
                @Override public void onError(Throwable t) { t.printStackTrace(); }
            });
}
```

`onPartialResponse` receives each token; `onCompleteResponse` receives the full
assistant message assembled from the stream plus usage metadata.

## Embeddings

```java
try (CortexJEmbeddingModel model = new CortexJEmbeddingModel(
        "sentence-transformers/all-MiniLM-L6-v2")) {
    Response<List<Embedding>> response =
            model.embedAll(List.of(TextSegment.from("a"), TextSegment.from("b")));
}
```

## Notes

- Constructor creates and owns a `CortexJ` platform; pass one explicitly to share.
- Multimodal/system prompts beyond the last user message are not translated by the
  adapter — use the direct CortexJ API for full `ChatMessage` control.
