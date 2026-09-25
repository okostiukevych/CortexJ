# Spring AI Integration

The `cortexj-spring-ai` adapter exposes a loaded CortexJ model as a Spring AI 1.0.x
`ChatModel`, `StreamingChatModel`, and `EmbeddingModel`. Requires Java 17+.

## Dependency

```xml
<dependency>
    <groupId>io.cortexj</groupId>
    <artifactId>cortexj-spring-ai</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## Blocking chat

```java
import io.cortexj.springai.CortexJChatModel;
import org.springframework.ai.chat.prompt.Prompt;

try (CortexJChatModel chat = new CortexJChatModel("Qwen/Qwen3-8B")) {
    ChatResponse response = chat.call(new Prompt(List.of(new UserMessage("hi"))));
    System.out.println(response.getResult().getOutput().getText());
}
```

## Streaming chat (reactor)

```java
try (CortexJStreamingChatModel chat = new CortexJStreamingChatModel("Qwen/Qwen3-8B")) {
    chat.stream(new Prompt(List.of(new UserMessage("hi"))))
        .map(r -> r.getResult().getOutput().getText())
        .subscribe(System.out::print);
}
```

Tokens flow from the CortexJ `TokenStream` into a `Flux<ChatResponse>`; cancellation of
the Flux cancels the underlying stream subscription (backpressure-safe buffering).

## Embeddings

```java
try (CortexJEmbeddingModel embeddings = new CortexJEmbeddingModel("sentence-transformers/all-MiniLM-L6-v2")) {
    float[] vector = embeddings.embed("Java Virtual Threads");
    EmbeddingResponse response = embeddings.call(new EmbeddingRequest(List.of("a", "b"),
            EmbeddingOptionsBuilder.builder().build()));
}
```

## Sharing an existing platform

Each adapter constructor creates (and owns) its own `CortexJ` platform by default.
Pass an existing platform to share caches, executors and event listeners:

```java
CortexJ platform = CortexJ.builder().build();
CortexJChatModel chat = new CortexJChatModel(platform, "Qwen/Qwen3-8B");   // platform NOT closed by the adapter
```

## Runtime selection

The model reference is planned by the CortexJ planner exactly like direct API usage:
ONNX/llama.cpp locally, `openai-remote` when `cortexj.remote.base.url` is configured.
Use `CortexJ.builder().allowRemoteRuntime(true)` plus environment variables to target
vLLM / llama-server / gateways.
