# Getting Started — Java 8

CortexJ supports Java 8 through the `cortexj-starter-java8` starter. The shared modules
(`cortexj-api`, `cortexj-core`, planners, repositories, ONNX runtime) are compiled to Java 8
bytecode, so the full platform boots on a real JDK 8.

## 1. Add the starter

```xml
<dependency>
    <groupId>io.cortexj</groupId>
    <artifactId>cortexj-starter-java8</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## 2. Boot the platform and run a model

```java
import io.cortexj.CortexJ;
import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelReference;
import io.cortexj.inference.GenerationRequest;
import io.cortexj.inference.GenerationResponse;
import io.cortexj.inference.TextGenerationCapability;

public class LegacyApp {
    public static void main(String[] args) {
        try (CortexJ platform = CortexJ.builder()
                .allowRemoteRuntime(true)
                .build()) {

            try (ModelHandle model = platform.models()
                    .load(ModelReference.of("Qwen/Qwen3-8B"))) {

                TextGenerationCapability generator =
                        model.capability(TextGenerationCapability.class);

                GenerationResponse response = generator.generate(
                        GenerationRequest.prompt("Explain MVCC briefly"));
                System.out.println(response.text());
            }
        }
    }
}
```

## 3. Requirements

- JDK 8 (any supported distribution). Wrong-JVM combinations (for example running the
  Java 25 starter on Java 8) fail fast with `CORTEXJ-CONF-0002` and a remediation hint.
- For local GGUF execution you need llama.cpp; for ONNX embeddings the bundled
  ONNX Runtime is enough (CPU).

## 4. What works on Java 8

- Model resolution (local, Hugging Face, plain HTTP URLs), cache, planner, lockfile,
  air-gap bundles, ONNX embeddings, OpenAI-compatible remote runtimes, streaming.
- Java 9+ features (virtual threads, `Flow` adapters, Spring AI / LangChain4j adapters)
  are NOT on the Java 8 classpath — they live behind JDK profiles.

## 5. CLI

The shaded CLI jar runs on Java 8 as well:

```bash
java -jar cortexj-cli-0.1.0-SNAPSHOT.jar doctor
java -jar cortexj-cli-0.1.0-SNAPSHOT.jar run Qwen/Qwen3-8B --prompt "hi"
```
