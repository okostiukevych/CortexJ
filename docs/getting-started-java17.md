# Getting Started — Java 17

On Java 17 use `cortexj-starter-java17`. Besides the shared platform this unlocks the
framework adapters: `cortexj-spring-ai` (Spring AI 1.0.x) and `cortexj-langchain4j`
(LangChain4j 1.0.x), both compiled for Java 17 bytecode.

## 1. Add the starter

```xml
<dependency>
    <groupId>io.cortexj</groupId>
    <artifactId>cortexj-starter-java17</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## 2. Optional: add a framework adapter

```xml
<dependency>
    <groupId>io.cortexj</groupId>
    <artifactId>cortexj-spring-ai</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

See [Spring AI](spring-ai.md) and [LangChain4j](langchain4j.md) guides for usage.

## 3. Same API on every LTS

Business code does not change between starters:

```java
try (CortexJ platform = CortexJ.builder().build();
     ModelHandle model = platform.models().load(ModelReference.of("Qwen/Qwen3-8B"))) {
    System.out.println(model.capability(TextGenerationCapability.class)
            .generate(GenerationRequest.prompt("hi")).text());
}
```

Compile this class against `cortexj-starter-java8` or `cortexj-starter-java17` — the
signatures are identical (AC-JAVA-003).

## 4. Java 17 specifics

- Text blocks and `switch` expressions are fine in your business code; the CortexJ
  shared modules stay Java 8 compatible.
- Platform behavior differences (executors, virtual threads) are selected by the
  `PlatformModule` SPI, not by your code.
