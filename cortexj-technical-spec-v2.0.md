# CortexJ — техническая спецификация и ТЗ v2.0

> **Официальное название продукта:** **CortexJ**.
>
> **Позиционирование:** *CortexJ — AI runtime orchestration and model portability platform for the JVM.*
>
> **Maven namespace:** `io.cortexj` / `cortexj-*`.
>
> **Статус:** Architecture/Product Specification — Draft for implementation.
>
> **Дата:** 2026-09-24.
>
> **Главное изменение относительно v1:** продукт больше не проектируется как ещё один JVM inference engine. Он проектируется как **унифицированная AI runtime orchestration и model portability platform для JVM**, которая выбирает и подключает подходящий execution backend и остаётся работоспособной без JInfer, Spring AI, LangChain4j или любого другого конкретного runtime/framework.

---

# 0. Executive decision record

В v2.0 зафиксированы следующие решения.

0. **Официальное название платформы — CortexJ.** Все public artifacts, CLI-команды, configuration namespaces и integration modules используют префикс `cortexj`.
1. **Мы не создаём конкурента PyTorch, llama.cpp, ONNX Runtime или JInfer.**
2. **Мы не создаём замену Spring AI или LangChain4j.**
3. **Мы создаём слой между Java/JVM application и конкретными AI runtimes.**
4. Основная ценность продукта — не само умножение матриц, а:
   - model discovery;
   - model portability;
   - runtime abstraction;
   - capability discovery;
   - hardware discovery;
   - execution planning;
   - runtime selection;
   - artifact selection;
   - conversion planning;
   - lifecycle management;
   - explainable diagnostics;
   - единый API на нескольких LTS-поколениях Java.
5. **JInfer является optional runtime provider**, а не обязательной зависимостью.
6. Первая версия должна работать **без JInfer** через минимум три execution path:
   - ONNX Runtime;
   - llama.cpp;
   - OpenAI-compatible remote runtime.
7. Дополнительные providers:
   - JInfer;
   - DJL;
   - TensorRT;
   - OpenVINO;
   - vendor-specific engines;
   - будущие JVM-native runtimes.
8. Поддерживаются LTS-линии:
   - Java 8;
   - Java 11;
   - Java 17;
   - Java 21;
   - Java 25.
9. Не создаются пять независимых библиотек. Используется:
   - единый shared API;
   - единое shared core;
   - единый SPI;
   - LTS-specific platform modules;
   - LTS-specific starters.
10. Пользователь выбирает starter своей версии Java:

```text
cortexj-starter-java8
cortexj-starter-java11
cortexj-starter-java17
cortexj-starter-java21
cortexj-starter-java25
```

11. Public API должен быть максимально одинаковым на всех поддерживаемых LTS.
12. Современные JVM не должны быть искусственно ограничены возможностями Java 8.
13. Java-version-specific возможности изолируются в отдельных implementation modules.
14. Внутренняя логика planner'а работает не по `if javaVersion == ...`, а по capabilities.
15. Spring AI и LangChain4j подключаются адаптерами поверх core.
16. Spring AI может использовать CortexJ как local/runtime implementation.
17. Legacy Java application может использовать CortexJ вообще без Spring.
18. Product goal:

> **Убрать недостаток AI-экосистемы как причину не выбирать Java/JVM для production AI-enabled продукта.**

---

# 1. Product definition

## 1.1. Краткая формулировка

CortexJ — multi-runtime AI orchestration и model portability platform для JVM, позволяющая приложению единообразно:

- ссылаться на AI-модель;
- находить модель и её artifacts;
- анализировать совместимость модели;
- определять доступные execution engines;
- определять hardware/JVM capabilities;
- выбирать подходящий runtime;
- выбирать наиболее подходящий model artifact;
- планировать конвертацию, если она нужна;
- загружать модель;
- выполнять inference;
- переключаться между runtime implementations без изменения business code;
- получать объяснение принятого execution decision;
- работать на Java 8/11/17/21/25 через один архитектурный контракт.

## 1.2. Product statement

> **CortexJ — one AI runtime layer for the JVM, from legacy Java 8 applications to modern Java 25 systems, across interchangeable local and remote runtimes.**

## 1.3. Что пользователь должен ощущать

Пользователь не должен начинать с вопроса:

> Как мне заставить эту модель работать с ONNX/llama.cpp/JInfer/DJL на моей машине?

Он должен начинать с:

```java
CortexJ platform = CortexJ.builder().build();

ModelHandle model = platform.models()
        .load(ModelReference.of("Qwen/Qwen3-8B"));
```

А platform должна определить технический execution path сама.

---

# 2. Problem statement

## 2.1. Проблема JVM AI сегодня

В JVM-экосистеме существуют качественные решения, но они находятся на разных уровнях:

```text
Spring AI / LangChain4j
        ↓
AI application orchestration
        ↓
-----------------------------------
        ↓
Model execution / serving
        ↓
ONNX / llama.cpp / JInfer / DJL / remote servers
        ↓
-----------------------------------
        ↓
CPU / CUDA / Metal / ROCm / accelerators
```

Пользователь вынужден самостоятельно решать:

- какой engine использовать;
- совместима ли модель;
- какой artifact нужен;
- есть ли GGUF/ONNX/Safetensors variant;
- какой quantization выбрать;
- хватит ли RAM/VRAM;
- совместим ли runtime с текущей Java;
- можно ли использовать GPU;
- что делать, если preferred backend отсутствует;
- как перейти на другой backend;
- как диагностировать несовместимость;
- как повторить deployment на другой машине.

## 2.2. Legacy gap

Особенно плохо выглядит сценарий существующего enterprise-приложения:

```text
Java 8 / 11
старый Spring или вообще без Spring
крупный production monolith
много legacy integrations
миграция JVM сейчас невозможна
```

Но business хочет:

- embeddings;
- classification;
- semantic search;
- local LLM;
- document extraction;
- speech/vision;
- AI-assisted workflows.

Наша платформа должна позволять внедрять AI capability без обязательной миграции всего приложения на новую JVM или Python microservice.

## 2.3. Modern JVM gap

Даже Java 21/25 application сталкивается с fragmented runtime ecosystem.

Наличие Spring AI решает application orchestration, но не полностью отвечает на вопросы:

```text
какая локальная модель?
какой runtime?
какой artifact?
какое hardware execution?
какая память?
какой fallback?
какая conversion strategy?
```

Именно этот слой является предметом CortexJ.

---

# 3. Competitive and ecosystem positioning

## 3.1. Spring AI

Spring AI рассматривается как **верхний application framework layer**.

Он отвечает за:

- portable model APIs;
- ChatClient;
- tool calling;
- MCP;
- advisors;
- RAG;
- vector stores;
- Spring Boot auto-configuration;
- provider integrations.

CortexJ не должна дублировать эти функции без необходимости.

Правильная зависимость:

```text
Spring Application
       ↓
Spring AI
       ↓
CortexJ Spring AI Adapter
       ↓
CortexJ Core
       ↓
Selected Runtime Provider
```

CortexJ Core **не зависит** от Spring AI.

## 3.2. LangChain4j

LangChain4j также рассматривается как application/orchestration framework.

Интеграция строится через adapter module.

```text
LangChain4j
    ↓
cortexj-langchain4j
    ↓
cortexj-core
```

## 3.3. JInfer / Quixotic AI

JInfer рассматривается как **один возможный execution engine**.

Он не является фундаментом платформы.

Запрещённая архитектура:

```text
CortexJ
      ↓
   JInfer
      ↓
 hardware
```

Требуемая архитектура:

```text
                  CortexJ
                        ↓
                   Runtime SPI
                        ↓
      ┌──────────┬──────┼───────┬─────────┐
      ↓          ↓      ↓       ↓         ↓
    ONNX     llama.cpp  DJL   JInfer   Future X
```

JInfer plugin должен быть удаляемым без нарушения работы остальных runtime providers.

## 3.4. ONNX Runtime

ONNX Runtime является одним из первичных execution backends.

Платформа не должна переизобретать:

- graph execution;
- execution providers;
- CUDA kernels;
- TensorRT integration;
- model optimization engine.

Она должна управлять ONNX Runtime через adapter/provider.

## 3.5. llama.cpp

llama.cpp является primary backend для:

- GGUF;
- quantized LLM;
- CPU inference;
- GPU offload;
- локального text generation;
- широкого consumer hardware.

## 3.6. DJL

DJL является optional provider и integration ecosystem.

Нельзя делать DJL dependency обязательной для core, поскольку platform должна работать независимо и охватывать разные Java LTS.

---

# 4. Product goals

## G-001. Unified model API

Business code не должен зависеть от конкретного execution engine.

## G-002. Runtime independence

Runtime implementation должна быть сменной через SPI.

## G-003. Multi-Java support

Поддерживать Java 8/11/17/21/25 без отдельных fork'ов продукта.

## G-004. Modern JVM optimization

Java 21/25 должны получать современные optimizations без изменения application code.

## G-005. Legacy enablement

Существующее Java 8/11 приложение должно иметь реальный execution path для AI workloads.

## G-006. No mandatory Python

Core execution workflow не должен требовать Python runtime.

## G-007. Explainable planning

Каждое автоматическое решение должно быть объяснимым.

## G-008. Deterministic deployment

Execution plan должен быть воспроизводимым при фиксированных:

- model revision;
- runtime versions;
- platform version;
- hardware profile;
- configuration.

## G-009. Progressive disclosure

Default workflow — минимальный.

Expert workflow — полностью управляемый.

## G-010. Ecosystem interoperability

Spring AI, LangChain4j и другие frameworks должны уметь использовать платформу через adapters.

---

# 5. Non-goals

В v1.x НЕ являются целью:

1. собственный general-purpose tensor engine;
2. собственный CUDA compiler;
3. собственный autograd;
4. distributed training;
5. замена PyTorch/JAX;
6. замена Spring AI;
7. замена LangChain4j;
8. полный RAG framework;
9. собственная vector database;
10. обязательное выполнение любой Hugging Face модели;
11. arbitrary Python code execution;
12. автоматическая поддержка неизвестных custom architectures;
13. cloud-provider billing/orchestration platform;
14. обязательная реализация собственного transformer inference engine.

---

# 6. Architectural principles

## AP-001. Core has no framework dependency

`cortexj-core` не зависит от Spring, Quarkus, Micronaut, LangChain4j.

## AP-002. Core has no mandatory inference-engine dependency

`cortexj-core` не зависит от ONNX Runtime, llama.cpp, JInfer или DJL.

## AP-003. Runtime is a plugin

Любой execution engine подключается через `RuntimeProvider`.

## AP-004. Model source is not runtime

Следующие понятия строго разделяются:

```text
Model Source
Model Revision
Model Artifact
Artifact Format
Model Architecture
Task Capability
Runtime Provider
Device
Execution Plan
```

## AP-005. AUTO path + expert path

Для каждого subsystem должен существовать:

- AUTO path;
- explicit configuration path.

## AP-006. Planner decisions are immutable

Созданный `ExecutionPlan` immutable.

## AP-007. Fail before load where possible

Несовместимость должна обнаруживаться до выделения больших объёмов памяти и запуска native runtime.

## AP-008. No hidden fallback

Fallback не должен происходить незаметно.

Он должен:

- фиксироваться в diagnostics;
- быть доступен в telemetry;
- иметь reason code.

## AP-009. Capabilities over version checks

Core logic должна работать с capability descriptors.

## AP-010. LTS-specific implementation modules

JVM-specific optimizations находятся за SPI и отдельными Maven artifacts.

---

# 7. Java compatibility strategy

## 7.1. Supported LTS matrix

| Java | Статус | Starter |
|---|---|---|
| 8 | Supported | `cortexj-starter-java8` |
| 11 | Supported | `cortexj-starter-java11` |
| 17 | Supported | `cortexj-starter-java17` |
| 21 | Supported / recommended for modern deployments | `cortexj-starter-java21` |
| 25 | Supported / latest optimized tier | `cortexj-starter-java25` |

## 7.2. Одна платформа, не пять fork'ов

Запрещается структура:

```text
project-java8/
project-java11/
project-java17/
project-java21/
project-java25/
```

с дублированной business/runtime logic.

Требуется:

```text
shared API/core
        ↓
platform SPI
        ↓
LTS-specific implementation modules
```

## 7.3. Shared modules

Shared modules должны компилироваться в bytecode, совместимый с минимальным baseline, если это практически возможно.

Предпочтительный baseline shared API: Java 8.

Это означает, что public shared API не должен требовать:

- records;
- sealed classes;
- `java.util.concurrent.Flow`;
- Virtual Threads;
- FFM;
- newer collection APIs.

## 7.4. LTS modules

Пример:

```text
cortexj-platform-java8
cortexj-platform-java11
cortexj-platform-java17
cortexj-platform-java21
cortexj-platform-java25
```

Каждый implementation module может использовать возможности своей целевой JVM.

## 7.5. Starter purpose

Starter собирает совместимый набор dependencies.

Он НЕ содержит отдельную копию core logic.

## 7.6. Migration guarantee

При переходе:

```text
Java 11 → Java 21
```

пользователь должен заменить primarily:

```diff
- cortexj-starter-java11
+ cortexj-starter-java21
```

Business code должен остаться неизменным, если он использует stable public API.

---

# 8. Maven distribution model

## 8.1. BOM

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.cortexj</groupId>
            <artifactId>cortexj-bom</artifactId>
            <version>${cortexj.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

## 8.2. Java 25 consumer

```xml
<dependency>
    <groupId>io.cortexj</groupId>
    <artifactId>cortexj-starter-java25</artifactId>
</dependency>
```

## 8.3. Java 11 consumer

```xml
<dependency>
    <groupId>io.cortexj</groupId>
    <artifactId>cortexj-starter-java11</artifactId>
</dependency>
```

## 8.4. Explicit runtime dependencies

При необходимости пользователь может подключать runtimes явно:

```xml
<dependency>
    <groupId>io.cortexj</groupId>
    <artifactId>cortexj-runtime-onnx</artifactId>
</dependency>

<dependency>
    <groupId>io.cortexj</groupId>
    <artifactId>cortexj-runtime-llamacpp</artifactId>
</dependency>
```

## 8.5. Optional JInfer

```xml
<dependency>
    <groupId>io.cortexj</groupId>
    <artifactId>cortexj-runtime-jinfer</artifactId>
</dependency>
```

Удаление этой dependency не должно ломать core.

---

# 9. Maven module architecture

```text
cortexj-parent
│
├── cortexj-bom
│
├── cortexj-api
├── cortexj-core
├── cortexj-spi
│
├── cortexj-model
├── cortexj-hub
├── cortexj-cache
├── cortexj-artifact
├── cortexj-conversion
├── cortexj-hardware
├── cortexj-planner
├── cortexj-streaming
├── cortexj-observability
│
├── cortexj-platform-java8
├── cortexj-platform-java11
├── cortexj-platform-java17
├── cortexj-platform-java21
├── cortexj-platform-java25
│
├── cortexj-runtime-onnx
├── cortexj-runtime-llamacpp
├── cortexj-runtime-remote-openai
├── cortexj-runtime-djl
├── cortexj-runtime-jinfer
│
├── cortexj-repository-huggingface
├── cortexj-repository-local
├── cortexj-repository-http
│
├── cortexj-spring-ai
├── cortexj-langchain4j
│
├── cortexj-cli
├── cortexj-server
│
├── cortexj-starter-java8
├── cortexj-starter-java11
├── cortexj-starter-java17
├── cortexj-starter-java21
└── cortexj-starter-java25
```

---

# 10. Dependency rules

## 10.1. Allowed direction

```text
api ← spi ← core
             ↑
             ├── planner
             ├── hub
             ├── hardware
             ├── cache
             └── artifact

runtime-* → spi/api
integration-* → core/api
starter-* → selected modules
```

## 10.2. Forbidden dependencies

- `core → spring-ai`
- `core → langchain4j`
- `core → jinfer`
- `core → onnxruntime`
- `core → llama.cpp binding`
- `planner → concrete runtime`

Planner должен работать с descriptors, а не concrete implementations.

---

# 11. Domain model

## 11.1. Core entities

Минимальный domain model:

```text
ModelReference
ModelRevision
ModelDescriptor
ModelCapability
ModelArtifact
ArtifactFormat
ArtifactVariant
RuntimeDescriptor
RuntimeCapability
DeviceDescriptor
HardwareProfile
EnvironmentProfile
ExecutionRequirement
ExecutionCandidate
ExecutionPlan
FallbackPlan
ConversionPlan
ModelHandle
ModelSession
InferenceRequest
InferenceResponse
```

## 11.2. ModelReference

Представляет логическую ссылку на модель.

```java
public interface ModelReference {

    String value();
}
```

Поддерживаемые формы:

```text
hf:Qwen/Qwen3-8B
file:/opt/models/model.gguf
http:https://internal/models/model.onnx
bundle:/models/company-model.bundle
```

Plain identifier может интерпретироваться default repository provider'ом.

## 11.3. ModelDescriptor

Должен содержать минимум:

- canonical ID;
- revision;
- architecture;
- tasks;
- modalities;
- parameter estimate;
- context information;
- artifact list;
- tokenizer/processor references;
- licensing metadata where available;
- trust metadata;
- compatibility hints.

---

# 12. Public API philosophy

## 12.1. Primary API is object-oriented

Основной API должен использовать instance-based facade.

```java
CortexJ platform = CortexJ.builder()
        .build();
```

Статические convenience API не являются фундаментом архитектуры.

## 12.2. Minimal workflow

```java
CortexJ platform = CortexJ.builder().build();

ModelHandle model = platform.models()
        .load(ModelReference.of("Qwen/Qwen3-8B"));

GenerationResponse response = model.generate(
        GenerationRequest.builder()
                .prompt("Explain CQRS")
                .build()
);

System.out.println(response.text());
```

## 12.3. Explicit planning

```java
ExecutionPlan plan = platform.planner()
        .plan(ExecutionRequest.builder()
                .model(ModelReference.of("Qwen/Qwen3-8B"))
                .build());
```

## 12.4. Expert control

```java
ExecutionRequest request = ExecutionRequest.builder()
        .model(ModelReference.of("Qwen/Qwen3-8B"))
        .preferredRuntime("llamacpp")
        .preferredDevice(DevicePreference.GPU)
        .memoryLimitBytes(20L * 1024 * 1024 * 1024)
        .fallbackPolicy(FallbackPolicy.ALLOW)
        .build();
```

---

# 13. Public API compatibility constraints

Shared public API должен оставаться Java 8-loadable.

## 13.1. Async API

Использовать `CompletionStage<T>` как baseline abstraction.

```java
CompletionStage<GenerationResponse> generateAsync(
        GenerationRequest request
);
```

## 13.2. Streaming API

Не использовать `Flow.Publisher` в shared baseline API.

Ввести собственный минимальный контракт:

```java
public interface TokenStream {

    StreamSubscription subscribe(TokenConsumer consumer);
}
```

Java 9+ adapter может предоставлять `Flow.Publisher`.

Reactor adapter может предоставлять `Flux`.

## 13.3. DTO

Shared DTO не используют records.

Records могут применяться внутри Java 17/21/25-specific modules, если не попадают в shared public signatures.

---

# 14. Repository subsystem

## 14.1. Responsibility

Repository subsystem отвечает только за discovery/resolution artifacts.

Он не выполняет модель.

## 14.2. SPI

```java
public interface ModelRepositoryProvider {

    RepositoryId id();

    boolean supports(ModelReference reference);

    ResolvedModel resolve(
            ModelReference reference,
            ResolutionContext context
    );
}
```

## 14.3. Initial providers

- Hugging Face;
- local filesystem;
- generic HTTP;
- local bundle.

Future:

- S3;
- OCI registry;
- Maven-like model repository;
- enterprise artifact repository.

## 14.4. Immutable revision resolution

Branch/tag reference должна по возможности преобразовываться в immutable revision/hash.

ExecutionPlan должен хранить resolved revision.

---

# 15. Model artifact subsystem

## 15.1. Supported artifact categories

```text
GGUF
ONNX
Safetensors
runtime-native package
tokenizer artifacts
processor artifacts
configuration artifacts
```

## 15.2. Artifact abstraction

```java
public interface ModelArtifact {

    ArtifactId id();

    ArtifactFormat format();

    long sizeBytes();

    Checksum checksum();

    ArtifactMetadata metadata();
}
```

## 15.3. Artifact inspection vs execution

Platform не обязана реализовывать полный tensor reader для каждого формата.

Для planning достаточно иметь `ArtifactInspector`:

```java
public interface ArtifactInspector {

    boolean supports(ArtifactFormat format);

    ArtifactInspection inspect(Path artifact);
}
```

Full reader/writer может быть отдельным optional module.

---

# 16. Runtime SPI

## 16.1. RuntimeProvider

```java
public interface RuntimeProvider {

    RuntimeId id();

    RuntimeDescriptor descriptor();

    RuntimeProbeResult probe(RuntimeProbeContext context);

    RuntimeSupport evaluate(RuntimeEvaluationRequest request);

    ModelRuntime open(RuntimeOpenRequest request);
}
```

## 16.2. Responsibilities

RuntimeProvider обязан сообщать:

- доступен ли runtime;
- почему недоступен;
- supported formats;
- supported model capabilities;
- supported devices;
- minimum JVM/platform requirements;
- native dependencies;
- expected memory characteristics;
- supported quantizations;
- concurrency characteristics;
- streaming support;
- structured output support if native;
- multimodal support.

## 16.3. RuntimeProvider не выбирает себя

Provider сообщает capability/support data.

Выбор делает Execution Planner.

## 16.4. ModelRuntime

```java
public interface ModelRuntime extends AutoCloseable {

    RuntimeId runtimeId();

    RuntimeModel load(RuntimeModelRequest request);
}
```

## 16.5. RuntimeModel

```java
public interface RuntimeModel extends AutoCloseable {

    ModelCapabilities capabilities();

    InferenceResponse execute(InferenceRequest request);

    CompletionStage<InferenceResponse> executeAsync(InferenceRequest request);
}
```

Specialized capabilities предоставляются через capability interfaces, а не giant interface.

---

# 17. Runtime capability interfaces

Вместо одного огромного `Model`:

```text
TextGenerationCapability
EmbeddingCapability
RerankingCapability
VisionCapability
SpeechToTextCapability
TextToSpeechCapability
ClassificationCapability
```

Пример:

```java
public interface TextGenerationCapability {

    GenerationResponse generate(GenerationRequest request);

    TokenStream stream(GenerationRequest request);
}
```

```java
public interface EmbeddingCapability {

    EmbeddingResponse embed(EmbeddingRequest request);
}
```

`ModelHandle` предоставляет typed capability lookup.

---

# 18. Mandatory runtime providers for MVP

## 18.1. ONNX Runtime provider

Обязателен.

Primary workloads:

- embeddings;
- classifiers;
- rerankers;
- vision models;
- speech models;
- compatible transformer graphs.

## 18.2. llama.cpp provider

Обязателен.

Primary workloads:

- GGUF;
- local LLM;
- quantized generation;
- CPU;
- GPU/offload according to available native build.

## 18.3. OpenAI-compatible remote provider

Обязателен.

Назначение:

- vLLM;
- TGI adapters where compatible;
- Ollama-compatible bridge where appropriate;
- internal model servers;
- any OpenAI-compatible endpoint.

Важно: это execution provider, не попытка заменить Spring AI cloud ecosystem.

## 18.4. Independence acceptance criterion

MVP test suite обязана иметь profile:

```text
JInfer dependency absent
```

и вся обязательная функциональность MVP должна проходить.

---

# 19. Optional runtime providers

## 19.1. JInfer provider

`cortexj-runtime-jinfer`.

Условия:

- optional;
- не транзитивен из core;
- не требуется для startup;
- version compatibility проверяется probe'ом;
- отсутствие не считается ошибкой.

## 19.2. DJL provider

Optional adapter.

## 19.3. Future providers

- TensorRT;
- OpenVINO;
- DirectML;
- MLX bridge where technically appropriate;
- vendor NPUs;
- custom enterprise runtime.

---

# 20. Runtime discovery

Runtime providers обнаруживаются через SPI registry.

Preferred baseline:

```java
ServiceLoader<RuntimeProvider>
```

с возможностью explicit registration:

```java
CortexJ platform = CortexJ.builder()
        .runtimeProvider(customProvider)
        .build();
```

## 20.1. Deterministic order

Порядок ServiceLoader не должен определять planner winner.

Planner сортирует candidates по deterministic scoring.

---

# 21. Environment and capability discovery

## 21.1. EnvironmentProfile

Должен описывать:

- JVM vendor;
- JVM feature version;
- OS;
- architecture;
- CPU logical/physical core hints;
- available memory;
- native access availability;
- vector capabilities where discoverable;
- accelerator inventory;
- runtime-installed capabilities.

## 21.2. HardwareProfile

```text
CPU
RAM
GPU/NPU list
VRAM where available
CUDA availability
ROCm availability
Metal availability
DirectML availability
instruction sets where discoverable
```

## 21.3. Probe safety

Hardware detection не должен:

- аварийно завершать JVM;
- требовать root;
- выполнять arbitrary shell commands без policy;
- зависать при отсутствии драйвера.

---

# 22. LTS-specific platform modules

## 22.1. Java 8 module

Responsibilities:

- baseline executor;
- JNI-compatible native bridge helpers;
- Java 8 diagnostics;
- legacy TLS/network compatibility handling where needed.

## 22.2. Java 11 module

Может использовать Java 11 APIs, не меняя shared contracts.

## 22.3. Java 17 module

Поддерживает современный baseline для enterprise deployments.

## 22.4. Java 21 module

Может реализовывать:

- Virtual Thread-based orchestration;
- improved concurrency implementation;
- newer JVM APIs.

## 22.5. Java 25 module

Может использовать современные stable JVM capabilities, включая FFM где это оправдано.

Vector/incubator API не должна быть обязательной частью core contract.

## 22.6. Starter validation

При запуске starter должен проверить, что он используется на допустимой JVM.

Например `starter-java25` на Java 21 должен завершать initialization с понятной ошибкой.

---

# 23. Execution Planner — core differentiator

Execution Planner является центральным интеллектуальным subsystem платформы.

## 23.1. Input

```text
ModelDescriptor
ModelArtifacts
EnvironmentProfile
HardwareProfile
Available RuntimeProviders
User Requirements
Policies
Runtime Health
Cache State
```

## 23.2. Output

`ExecutionPlan` содержит:

- resolved model revision;
- selected artifact;
- selected runtime;
- selected device;
- selected precision/quantization where applicable;
- memory estimate;
- conversion steps;
- download steps;
- native requirements;
- fallback candidates;
- scoring explanation;
- warnings;
- reproducibility data.

## 23.3. Planner pipeline

```text
Resolve model
    ↓
Inspect artifacts
    ↓
Discover environment
    ↓
Discover runtimes
    ↓
Build candidate set
    ↓
Apply hard constraints
    ↓
Estimate resources
    ↓
Apply user policies
    ↓
Score candidates
    ↓
Select winner
    ↓
Build fallback chain
    ↓
Produce explanation
```

---

# 24. Hard constraints

Candidate исключается, если:

- runtime отсутствует;
- JVM incompatible;
- artifact format unsupported;
- model capability unsupported;
- native binary unavailable;
- OS/architecture unsupported;
- required accelerator unavailable;
- memory exceeds hard configured limit;
- model license policy blocks execution;
- trust policy blocks artifact;
- explicit user requirement conflicts;
- conversion required but no converter exists.

---

# 25. Soft scoring

Оставшиеся candidates получают score.

Пример компонентов:

```text
runtime compatibility confidence
estimated latency
estimated throughput
memory pressure
startup cost
artifact download cost
conversion cost
device utilization
runtime maturity weight
user preference
locality preference
energy/cost policy (future)
```

Planner не должен иметь hard-coded огромный `if/else`.

Используется policy/specification model.

---

# 26. Explainable plan

Каждый plan должен уметь генерировать human-readable explanation.

Пример:

```text
Model: Qwen/Qwen3-8B
Resolved revision: 7f62...

Environment:
Java 21
Linux x86_64
NVIDIA RTX 4090
24 GB VRAM

Available runtimes:
- llama.cpp: supported
- ONNX Runtime: supported for available ONNX artifact
- JInfer: not installed

Selected:
llama.cpp
Artifact: qwen3-8b-q6_k.gguf
Device: CUDA

Reasons:
+ native GGUF artifact already available
+ estimated VRAM fits configured safety margin
+ generation capability supported
+ lower estimated startup/conversion cost

Fallback:
ONNX Runtime / CPU
```

Machine-readable reasons должны иметь stable reason codes.

---

# 27. Planner extension model

```java
public interface ExecutionPolicy {

    PolicyDecision evaluate(ExecutionCandidate candidate, PlanningContext context);
}
```

Примеры:

```text
MemorySafetyPolicy
PreferredRuntimePolicy
PreferredDevicePolicy
LocalOnlyPolicy
NoNativePolicy
AirGapPolicy
LatencyPolicy
ThroughputPolicy
EnterpriseTrustPolicy
```

Policies компонуются без nested conditional tree.

---

# 28. User override model

Пользователь может задавать:

- required runtime;
- preferred runtime;
- forbidden runtimes;
- required device;
- preferred device;
- maximum RAM;
- maximum VRAM;
- no download;
- local only;
- offline;
- allowed licenses;
- allowed repositories;
- fallback enabled/disabled;
- required artifact format;
- preferred quantization.

Hard user requirement никогда не должен незаметно игнорироваться.

---

# 29. Memory estimation

## 29.1. Purpose

Минимизировать OOM и аварийные native failures.

## 29.2. Estimate components

Для LLM:

```text
model weights
runtime overhead
KV cache
context length
batch size
scratch buffers
GPU offload
safety reserve
```

Для ONNX:

```text
model size
initializer memory
activation estimate
execution provider overhead
batch estimate
```

## 29.3. Confidence

Estimate должен иметь confidence:

```text
HIGH
MEDIUM
LOW
UNKNOWN
```

Planner обязан учитывать uncertainty safety margin.

---

# 30. Model portability

## 30.1. Principle

Пользователь указывает logical model, а не обязательно конкретный engine artifact.

Platform пытается найти наиболее подходящий artifact.

## 30.2. Example

Repository содержит:

```text
model.safetensors
model.onnx
model-q4.gguf
model-q6.gguf
```

На Java 11 CPU machine planner может выбрать GGUF.

На CUDA server может выбрать ONNX CUDA artifact.

На Java 25 с JInfer plugin может появиться ещё один candidate.

## 30.3. No false promise

Platform не обещает автоматическое выполнение любой модели.

Она обязана точно объяснить, какие paths доступны и почему.

---

# 31. Compatibility levels

## Level A — Direct artifact execution

Подходящий artifact уже существует и supported runtime доступен.

## Level B — Artifact variant selection

Repository содержит несколько форматов/quantizations; planner выбирает один.

## Level C — Deterministic conversion

Artifact можно преобразовать зарегистрированным converter'ом без Python.

## Level D — External conversion required

Platform может сформировать recommendation, но не должна скрытно запускать Python.

## Level E — Unsupported architecture/runtime combination

Возвращается structured support report.

---

# 32. Conversion subsystem

## 32.1. Converter SPI

```java
public interface ModelConverter {

    ConverterId id();

    ConversionSupport evaluate(ConversionRequest request);

    ConversionResult convert(ConversionRequest request);
}
```

## 32.2. Conversion plan

Conversion является частью ExecutionPlan.

## 32.3. No implicit destructive conversion

Original artifacts immutable.

Generated artifact сохраняется отдельно с provenance manifest.

## 32.4. Provenance

Manifest содержит:

- source revision;
- source checksums;
- converter ID/version;
- target format;
- quantization;
- parameters;
- output checksum;
- timestamp;
- platform version.

---

# 33. Cache subsystem

## 33.1. Goals

- deduplication;
- deterministic cache;
- offline reuse;
- safe concurrent download;
- corruption detection.

## 33.2. Cache keys

Cache key не должен зависеть только от mutable model name.

Использовать:

```text
repository
resolved revision
artifact identity
checksum
```

## 33.3. Locks

Concurrent processes не должны дважды повреждать один artifact.

## 33.4. Eviction

Policies:

```text
LRU
size cap
pinned models
age cap
manual
```

---

# 34. Download manager

Требования:

- streaming download;
- checksum validation;
- temporary file + atomic move;
- resume where supported;
- timeout;
- retry policy;
- proxy support;
- authentication provider;
- progress events;
- cancellation.

---

# 35. Model trust and security

## 35.1. No arbitrary repository code execution

Загрузка модели не должна исполнять remote Python/custom repository source code.

## 35.2. Trust policies

```text
TRUSTED_ONLY
ALLOW_UNSIGNED_WITH_WARNING
CUSTOM
```

## 35.3. Artifact limits

Перед download/load проверять:

- declared size;
- configured max size;
- path traversal;
- archive bombs;
- malformed metadata limits.

## 35.4. Native runtime security

Native binaries проверяются по:

- platform;
- checksum/signature;
- known source;
- version compatibility.

---

# 36. Native runtime packaging

## 36.1. Platform matrix

Пример:

```text
linux-x86_64
linux-aarch64
windows-x86_64
macos-aarch64
macos-x86_64 (where supported)
```

## 36.2. Native artifact resolver

Native libs не должны распаковываться в непредсказуемые global paths.

## 36.3. Override

Enterprise user может предоставить preinstalled native runtime.

---

# 37. Streaming

## 37.1. Baseline abstraction

```java
public interface TokenStream extends AutoCloseable {

    StreamSubscription subscribe(TokenConsumer consumer);
}
```

## 37.2. Requirements

- cancellation;
- backpressure abstraction;
- terminal error;
- terminal complete;
- token metadata;
- usage metadata when available.

## 37.3. Adapters

- Java Flow adapter;
- Reactor adapter;
- Kotlin Flow adapter — future;
- Spring AI streaming adapter.

---

# 38. Concurrency model

## 38.1. Shared contract

Concurrency implementation скрыта от API.

## 38.2. Java 8/11/17

Используются compatible executors.

## 38.3. Java 21+

I/O orchestration может использовать Virtual Threads.

## 38.4. Native calls

Planner/runtime descriptor должен знать blocking/thread-safe properties runtime.

## 38.5. No uncontrolled thread creation

Каждый subsystem использует injected execution resources/policies.

---

# 39. Runtime health and circuit behavior

RuntimeProvider может сообщать:

```text
AVAILABLE
DEGRADED
UNAVAILABLE
MISCONFIGURED
```

Если runtime падает при initialization, он не должен автоматически повторяться бесконечно.

Runtime health может участвовать в planning.

---

# 40. Fallback model

## 40.1. Fallback modes

```text
DISABLED
LOAD_TIME_ONLY
INFERENCE_SAFE
CUSTOM
```

## 40.2. Safety

Переключение runtime не всегда семантически эквивалентно.

Fallback допускается только при compatible task/model semantics.

## 40.3. Diagnostics

Fallback всегда фиксируется.

---

# 41. Model lifecycle

States:

```text
DISCOVERED
RESOLVING
DOWNLOADING
PREPARING
LOADING
READY
DEGRADED
CLOSING
CLOSED
FAILED
```

Переходы централизованы в lifecycle component.

---

# 42. Resource management

Все heavyweight resources должны быть `AutoCloseable`.

```java
try (ModelHandle model = platform.models().load(reference)) {
    ...
}
```

Spring adapter закрывает resources через lifecycle bean management.

---

# 43. Text generation API

```java
GenerationRequest request = GenerationRequest.builder()
        .prompt("Explain MVCC")
        .temperature(0.2)
        .maxTokens(512)
        .build();

GenerationResponse response = model
        .capability(TextGenerationCapability.class)
        .generate(request);
```

Convenience method допускается, но domain capability API является основой.

---

# 44. Chat API

Chat representation:

```text
SYSTEM
USER
ASSISTANT
TOOL
```

Shared DTO Java 8-compatible.

Chat templates должны быть runtime/model-aware.

Tool orchestration не является core responsibility; Spring AI/LangChain4j adapters могут реализовать higher-level loop.

---

# 45. Embeddings

```java
EmbeddingCapability embeddings = model
        .capability(EmbeddingCapability.class);

EmbeddingResponse response = embeddings.embed(
        EmbeddingRequest.builder()
                .text("Java Virtual Threads")
                .build()
);
```

Batch embeddings обязательны.

---

# 46. Reranking

Ввести отдельную capability, не смешивать с embeddings.

```java
RerankingResponse rerank(RerankingRequest request);
```

---

# 47. Vision and multimodal

Multimodal support — capability-based.

Не все runtimes обязаны поддерживать.

Planner должен учитывать:

- processor requirements;
- image/audio support;
- artifact compatibility;
- context constraints.

---

# 48. Structured output

Platform может предоставлять common structured-output helper.

Но runtime-specific constrained decoding capability должна описываться explicitly.

Fallback:

```text
native schema constraint
→ grammar constraint
→ prompt + validation
```

Нельзя представлять prompt-only parsing как guaranteed structured decoding.

---

# 49. Declarative Java API

Future/high-level module:

```java
public interface InvoiceExtractor {

    Invoice extract(String text);
}
```

Proxy generation не входит в минимальное core.

Рекомендуется отдельный artifact:

```text
cortexj-declarative
```

Чтобы runtime platform не превратилась в application framework.

---

# 50. Spring AI integration

## 50.1. Position

Spring AI не заменяется.

## 50.2. Adapter responsibilities

`cortexj-spring-ai` реализует необходимые Spring AI model interfaces поверх CortexJ.

Пример conceptual flow:

```text
Spring AI ChatClient
       ↓
CortexJChatModel
       ↓
ModelHandle
       ↓
ExecutionPlan
       ↓
RuntimeProvider
```

## 50.3. Configuration

Пример:

```yaml
cortexj:
  models:
    chat:
      id: Qwen/Qwen3-8B
      runtime: auto
      device: auto
```

## 50.4. Spring version boundaries

Spring integration artifacts могут иметь собственные JVM requirements и version lines, не меняя core compatibility.

---

# 51. LangChain4j integration

Adapter должен предоставлять совместимые model implementations.

Он не должен вносить LangChain4j types в core.

---

# 52. Remote runtime boundary

## 52.1. Purpose

Remote runtime нужен для execution servers, а не для дублирования всей cloud-provider экосистемы.

## 52.2. Initial protocol

OpenAI-compatible protocol.

## 52.3. Typical targets

- vLLM;
- internal inference gateway;
- compatible model server;
- Ollama bridge where protocol compatibility allows.

## 52.4. Spring AI boundary

Если задача — интеграция непосредственно с OpenAI/Anthropic/Gemini SaaS, предпочтительным higher-level layer остаётся Spring AI/LangChain4j.

---

# 53. Configuration model

Configuration sources:

1. programmatic builder;
2. system properties;
3. environment variables;
4. config file optional;
5. framework adapter configuration.

Priority должен быть documented и deterministic.

Никакого hidden global mutable singleton configuration.

---

# 54. Diagnostics API

```java
SupportReport report = platform.support()
        .inspect(ModelReference.of("Qwen/Qwen3-8B"));
```

Report содержит:

- resolved model;
- artifacts;
- runtime candidates;
- rejected candidates;
- reasons;
- required downloads;
- conversion options;
- memory estimates;
- recommended next actions.

---

# 55. Error taxonomy

Минимальные категории:

```text
ModelResolutionException
ArtifactResolutionException
ArtifactIntegrityException
RuntimeUnavailableException
RuntimeCompatibilityException
PlanningException
InsufficientMemoryException
NativeRuntimeException
ConversionException
ModelLoadException
InferenceException
SecurityPolicyException
ConfigurationException
```

Каждая ошибка содержит:

- code;
- human message;
- technical details;
- remediation suggestions;
- relevant IDs.

---

# 56. Error UX

Плохо:

```text
UnsatisfiedLinkError
```

Хорошо:

```text
JVM-AI-RUNTIME-1042

llama.cpp CUDA runtime could not be loaded.

Detected:
- OS: Linux x86_64
- Java: 21
- GPU: NVIDIA RTX 4090
- CUDA driver: available

Attempted native artifact:
...

Alternatives:
1. use CPU llama.cpp runtime
2. enable ONNX CUDA runtime
3. provide custom native library path
```

---

# 57. Observability

## 57.1. Metrics

- planning duration;
- model resolution duration;
- download duration;
- load duration;
- first-token latency;
- total inference latency;
- tokens/sec;
- queue wait;
- runtime errors;
- fallback count;
- memory estimates/actual where obtainable.

## 57.2. Tracing

Spans:

```text
model.resolve
execution.plan
artifact.download
artifact.convert
runtime.open
model.load
inference.execute
inference.stream
```

## 57.3. Sensitive data

Prompts/responses не должны логироваться по умолчанию.

---

# 58. OpenTelemetry integration

Core публикует neutral observability events.

OpenTelemetry adapter — отдельный module.

---

# 59. CLI

CLI полезен для диагностики и developer experience.

Команды:

```text
cortexj doctor
cortexj inspect <model>
cortexj plan <model>
cortexj pull <model>
cortexj runtimes
cortexj hardware
cortexj run <model>
cortexj cache list
cortexj cache clean
```

## 59.1. `doctor`

Показывает:

- Java version;
- detected starter/platform module;
- hardware;
- native capabilities;
- installed runtimes;
- incompatible runtimes;
- recommendations.

---

# 60. Optional server

`cortexj-server` предоставляет OpenAI-compatible local endpoint поверх selected runtime.

Он не является обязательным для embedded usage.

Endpoints v1:

```text
/v1/chat/completions
/v1/embeddings
/v1/models
```

Future multimodal endpoints отдельно.

---

# 61. Embedded-first principle

Главный сценарий:

```text
Java process
    ↓
CortexJ
    ↓
embedded runtime
```

Server mode является дополнительным.

---

# 62. Offline and air-gapped mode

## 62.1. Offline

Запрещает network resolution.

## 62.2. Bundle

Поддержать portable model bundle:

```text
manifest
model artifacts
tokenizer/processor
checksums
license metadata
optional runtime hints
```

## 62.3. Enterprise import

CLI должен уметь проверить bundle до deployment.

---

# 63. Model lockfile

Для reproducibility:

```yaml
model: Qwen/Qwen3-8B
revision: 7f62...
artifact:
  file: qwen3-q6.gguf
  sha256: ...
runtime:
  id: llamacpp
  version: ...
planner:
  platformVersion: ...
```

Lockfile можно использовать в strict deployment mode.

---

# 64. Security threat model

Учитывать:

- malicious model metadata;
- oversized artifacts;
- path traversal;
- native library hijacking;
- checksum spoofing;
- SSRF through repository URL;
- credential leakage;
- prompt logging;
- untrusted conversion plugins;
- supply-chain attack;
- model license violations.

---

# 65. Plugin security

Third-party plugins могут выполнять код в JVM.

Поэтому platform должна:

- явно перечислять loaded plugins;
- показывать source/version;
- поддерживать allowlist;
- не скачивать executable plugin автоматически из model repository.

---

# 66. License governance

ModelDescriptor может содержать license metadata.

Enterprise policy может блокировать модели по лицензии.

Platform не должна интерпретировать юридическую допустимость самостоятельно; она применяет configured policy.

---

# 67. Testing strategy

## 67.1. Unit tests

Для каждого subsystem.

## 67.2. Runtime Provider TCK

Создать reusable Technology Compatibility Kit.

Каждый runtime provider обязан проходить contract tests:

- probe;
- support evaluation;
- load/close;
- invalid artifact;
- cancellation;
- concurrency declaration;
- error mapping;
- capability reporting.

## 67.3. Planner tests

Planner тестируется на synthetic matrices.

Пример:

```text
Java 11 + CUDA + ONNX + no JInfer
→ must not select JInfer
```

```text
Java 25 + JInfer installed + GGUF + Metal
→ both JInfer and llama.cpp may become candidates
→ deterministic policy decides
```

## 67.4. Golden plan tests

ExecutionPlan serialized result сравнивается с golden fixtures.

## 67.5. Differential tests

Там, где возможно, output сравнивается между runtimes с tolerance.

---

# 68. Multi-JDK CI matrix

Обязательные lanes:

```text
Java 8
Java 11
Java 17
Java 21
Java 25
```

Для shared core — полный common test suite.

Для platform module — LTS-specific suite.

---

# 69. OS/architecture CI

Minimum:

```text
Linux x86_64
Windows x86_64
macOS arm64
```

Desired:

```text
Linux arm64
macOS x86_64 where practical
```

---

# 70. GPU test infrastructure

GPU tests отделяются от ordinary CI.

Profiles:

```text
NVIDIA CUDA
Apple Metal
CPU-only
```

No GPU test должен silently skip critical compatibility assertions.

---

# 71. Performance tests

Platform benchmark измеряет не только raw inference.

Отдельно:

- planning overhead;
- discovery overhead;
- startup/load;
- cache hit;
- cache miss;
- first token;
- steady-state throughput.

Raw backend performance не приписывается платформе как собственное достижение.

---

# 72. Performance budgets

Цели platform layer:

- planner overhead negligible относительно model load;
- no per-token reflection in hot path;
- no unnecessary copies across adapter boundary;
- no double buffering large model artifacts;
- streaming events lightweight.

---

# 73. API stability

Semantic versioning.

Разделить:

```text
Public API
Stable SPI
Experimental SPI
Internal
```

Experimental packages должны быть явно помечены.

---

# 74. Binary compatibility

Использовать binary compatibility checks в CI.

Для shared API особенно важно не ломать Java 8/11 consumers minor releases.

---

# 75. Coding standards

Основные требования:

- SOLID;
- OOP для domain boundaries;
- composition over inheritance;
- immutable value objects;
- DRY;
- functional transformations там, где они улучшают читаемость;
- stream API допустим в модулях, где он совместим с target Java;
- избегать giant service classes;
- избегать nested static domain classes;
- избегать switch-based runtime architecture;
- использовать Strategy/Specification/Policy/Adapter;
- dependency inversion;
- `@SuppressWarnings` только локально и обоснованно;
- Lombok разрешён как build-time implementation detail, но не является dependency requirement для consumers.

---

# 76. Architecture patterns

## Strategy

Runtime selection strategies.

## Specification

Compatibility constraints.

## Policy

Planner policies.

## Adapter

Runtime/framework integrations.

## Repository

Model source abstraction.

## Facade

High-level `CortexJ`.

## Factory

Runtime session creation.

## State machine

Lifecycle.

## Chain of Responsibility

Diagnostics/remediation enrichment where appropriate.

---

# 77. Public packages

Provisional:

```text
io.cortexj
io.cortexj.model
io.cortexj.runtime
io.cortexj.planner
io.cortexj.repository
io.cortexj.hardware
io.cortexj.stream
io.cortexj.diagnostics
```

SPI:

```text
io.cortexj.spi.runtime
io.cortexj.spi.repository
io.cortexj.spi.conversion
io.cortexj.spi.hardware
io.cortexj.spi.policy
```

Implementations не должны находиться в public API package.

---

# 78. Example — Java 8 legacy application

Maven:

```xml
<dependency>
    <groupId>io.cortexj</groupId>
    <artifactId>cortexj-starter-java8</artifactId>
    <version>1.0.0</version>
</dependency>
```

Code:

```java
CortexJ platform = CortexJ.builder().build();

ModelHandle model = platform.models()
        .load(ModelReference.of("company/document-model"));

GenerationResponse response = model
        .capability(TextGenerationCapability.class)
        .generate(GenerationRequest.builder()
                .prompt("Extract the important fields")
                .build());
```

Possible execution:

```text
Java 8
→ ONNX Runtime provider
→ CPU/CUDA depending environment
```

или:

```text
Java 8
→ llama.cpp JNI provider
```

JInfer не требуется.

---

# 79. Example — Java 21 application

Dependency:

```xml
<artifactId>cortexj-starter-java21</artifactId>
```

Application code остаётся тем же.

Platform implementation может использовать Virtual Threads для orchestration.

---

# 80. Example — Java 25 + optional JInfer

```xml
<dependency>
    <groupId>io.cortexj</groupId>
    <artifactId>cortexj-starter-java25</artifactId>
</dependency>

<dependency>
    <groupId>io.cortexj</groupId>
    <artifactId>cortexj-runtime-jinfer</artifactId>
</dependency>
```

Planner видит дополнительного candidate.

```text
llama.cpp
ONNX
JInfer
```

Если JInfer получает лучший score — выбирается он.

Если dependency удалить — application code не изменяется.

---

# 81. Example — explicit ONNX

```java
ExecutionRequest request = ExecutionRequest.builder()
        .model(ModelReference.of("hf:company/model"))
        .requiredRuntime("onnx")
        .build();

ModelHandle model = platform.models().load(request);
```

Если ONNX path невозможен, platform должна вернуть понятную ошибку, а не silently выбрать llama.cpp.

---

# 82. Example — plan before execution

```java
ExecutionPlan plan = platform.planner()
        .plan(ExecutionRequest.builder()
                .model(ModelReference.of("Qwen/Qwen3-8B"))
                .build());

System.out.println(plan.explanation());
```

Это один из главных UX сценариев продукта.

---

# 83. Example — Spring AI

Conceptually:

```java
@Bean
ChatModel chatModel(CortexJ platform) {
    return new CortexJSpringChatModel(platform, "Qwen/Qwen3-8B");
}
```

Business service продолжает использовать Spring AI API.

---

# 84. Example — runtime plugin implementation

```java
public final class CustomRuntimeProvider implements RuntimeProvider {

    @Override
    public RuntimeId id() {
        return RuntimeId.of("company-runtime");
    }

    @Override
    public RuntimeSupport evaluate(RuntimeEvaluationRequest request) {
        // provider evaluates compatibility only
        // planner makes final selection
        return ...;
    }

    @Override
    public ModelRuntime open(RuntimeOpenRequest request) {
        return ...;
    }
}
```

No changes in core required.

---

# 85. Planner scoring architecture

Не использовать central switch:

```java
switch (runtimeType) {
    case ONNX:
    case LLAMA:
    case JINFER:
}
```

Вместо этого candidate attributes + policies.

```text
RuntimeProvider
   ↓
RuntimeSupport
   ↓
ExecutionCandidate
   ↓
List<ExecutionPolicy>
   ↓
CandidateScore
```

---

# 86. Runtime descriptors

Descriptor пример:

```text
id: llamacpp
version: 1.x
formats: [GGUF]
tasks: [TEXT_GENERATION, EMBEDDING?]
devices: [CPU, CUDA, METAL, ...]
minJava: provider-specific
streaming: true
native: true
threadSafety: SESSION_SCOPED
```

Fields должны быть extensible.

---

# 87. Runtime-specific options

Не загрязнять common request десятками backend-specific fields.

Использовать typed extension options:

```java
request.runtimeOptions(
        RuntimeOptions.forRuntime("llamacpp")
                .put("gpuLayers", 40)
);
```

Для first-party providers предпочтительны typed option builders в provider artifacts.

---

# 88. Hardware/device abstraction

Devices:

```text
CPU
GPU
NPU
REMOTE
AUTO
```

Concrete accelerator metadata отдельно.

Не вводить `CUDA` как generic device type; CUDA — execution ecosystem/backend capability GPU.

---

# 89. Artifact selection

Planner должен учитывать:

- file size;
- format;
- quantization;
- runtime compatibility;
- device compatibility;
- download availability;
- local cache;
- memory estimate;
- quality policy.

---

# 90. Quality/quantization policy

Пользователь может задавать profile:

```text
QUALITY_FIRST
BALANCED
MEMORY_FIRST
LATENCY_FIRST
CUSTOM
```

Profile преобразуется в planner policies.

Не скрывать выбранную quantization.

---

# 91. Reproducibility mode

Modes:

```text
FLEXIBLE
PINNED_MODEL
PINNED_PLAN
```

`PINNED_PLAN` запрещает planner выбирать другой runtime/artifact без explicit override.

---

# 92. Support report

`SupportReport` должен отвечать на вопрос:

> Можно ли запустить эту модель в этом приложении на этой машине и что для этого нужно?

Он должен быть доступен без загрузки модели.

---

# 93. Maven plugin — future/convenience

`cortexj-maven-plugin`:

```text
mvn cortexj:doctor
mvn cortexj:plan
mvn cortexj:verify
```

Не является runtime dependency.

---

# 94. Build-time verification

Future goal: Maven plugin может проверять deployment profile:

- correct starter;
- native runtime availability;
- pinned model bundle;
- checksum manifest.

---

# 95. JPMS

Shared modules должны иметь clean module boundaries.

JPMS support желателен, но не должен нарушать Java 8 baseline shared artifacts.

Для Java 9+ могут использоваться optional module-info strategies/build profiles.

---

# 96. GraalVM/native image

Не обещать full native-image compatibility v1.0.

Core/planner должны избегать unnecessary reflection.

Runtime adapters документируют compatibility individually.

---

# 97. Serialization

Core не должен жёстко зависеть от Jackson.

Internal/public model values не должны требовать конкретной serialization library.

CLI/server/framework adapters могут подключать Jackson отдельно.

---

# 98. Logging

Использовать facade-compatible logging abstraction; не тянуть тяжёлую logging implementation.

Никаких prompt bodies по умолчанию.

---

# 99. Dependency hygiene

Core dependencies должны быть минимальными.

Heavy native/runtime dependencies находятся только в соответствующих provider modules/starters.

---

# 100. Supply-chain requirements

- dependency locking for build;
- SBOM publication;
- checksums;
- signed releases where possible;
- reproducible build goal;
- CVE scanning;
- native binary provenance.

---

# 101. Documentation requirements

Документация должна содержать отдельные guides:

```text
Getting Started Java 8
Getting Started Java 11
Getting Started Java 17
Getting Started Java 21
Getting Started Java 25
Spring AI
LangChain4j
ONNX
llama.cpp
Offline deployment
Enterprise security
Writing a RuntimeProvider
Writing a RepositoryProvider
Writing a Planner Policy
```

---

# 102. Runtime Provider developer guide

Обязательно документировать:

1. SPI lifecycle;
2. capability declaration;
3. probe behavior;
4. support evaluation;
5. error mapping;
6. native resource lifecycle;
7. thread-safety declaration;
8. TCK execution.

---

# 103. MVP scope

MVP обязан включать:

1. shared API;
2. shared SPI;
3. core platform;
4. Java 17/21/25 starters initially during development;
5. architecture готовую для Java 8/11;
6. Java 8/11 support до публичного 1.0;
7. local filesystem repository;
8. Hugging Face repository resolver;
9. model descriptor;
10. artifact inspection;
11. hardware profile;
12. runtime discovery;
13. ONNX Runtime provider;
14. llama.cpp provider;
15. OpenAI-compatible remote provider;
16. Execution Planner;
17. explainable plan;
18. cache;
19. diagnostics;
20. text generation where runtime supports;
21. embeddings;
22. streaming abstraction;
23. CLI `doctor/inspect/plan`.

JInfer provider **не обязателен для MVP**.

---

# 104. MVP explicitly excludes

- own tensor engine;
- JInfer mandatory dependency;
- arbitrary Safetensors direct execution;
- training;
- fine-tuning;
- RAG engine;
- vector database;
- agent framework;
- cloud SaaS provider matrix;
- automatic support for arbitrary HF custom Python models.

---

# 105. v1.0 scope

В v1.0 дополнительно обязательно:

- Java 8 starter;
- Java 11 starter;
- Java 17 starter;
- Java 21 starter;
- Java 25 starter;
- compatibility TCK;
- model lockfile;
- air-gap bundle;
- Spring AI integration;
- LangChain4j integration;
- runtime fallback policies;
- production observability;
- security hardening;
- native packaging matrix.

Optional for 1.0:

- JInfer provider;
- DJL provider.

---

# 106. Milestone roadmap

## M0 — Architecture skeleton

- parent/BOM;
- API;
- SPI;
- module boundaries;
- ArchUnit rules;
- compatibility policy.

## M1 — Model resolution

- local repository;
- HF resolver;
- immutable revision;
- artifact metadata;
- cache.

## M2 — Runtime SPI + ONNX

- provider registry;
- ONNX provider;
- embeddings demonstration;
- provider TCK draft.

## M3 — llama.cpp

- native loading;
- GGUF model path;
- text generation;
- streaming.

## M4 — Execution Planner

- candidates;
- hard constraints;
- scoring;
- explainability;
- memory estimates.

## M5 — Multi-JDK platform

- Java 17;
- Java 21;
- Java 25;
- separate starters;
- common API validation.

## M6 — Legacy support

- Java 11;
- Java 8;
- supported backend matrix;
- legacy tests.

## M7 — Production integration

- Spring AI;
- LangChain4j;
- OpenTelemetry;
- lockfile;
- airgap.

## M8 — Extended runtimes

- JInfer adapter;
- DJL adapter;
- vendor providers.

## M9 — Portability/conversion expansion

- converters;
- richer artifact transformations;
- multimodal planning.

---

# 107. Definition of Done — MVP

MVP завершён, если:

1. Одна и та же logical API может загрузить compatible model через ONNX или llama.cpp.
2. Planner выбирает runtime автоматически.
3. Пользователь может потребовать конкретный runtime.
4. `plan` объясняет выбор.
5. JInfer отсутствует в classpath и MVP работает.
6. HF model resolution работает для supported artifacts.
7. Cache checksum-safe.
8. Basic hardware profile работает на primary OS targets.
9. Streaming работает через common abstraction.
10. Provider TCK проходит для ONNX/llama.cpp.

---

# 108. Definition of Done — v1.0

v1.0 завершён, если:

1. Java 8/11/17/21/25 имеют supported starter.
2. Shared public API совместим согласно published matrix.
3. Legacy Java 8 demo выполняет минимум один useful local AI workload без Python.
4. Java 25 demo использует optimized platform module.
5. ONNX и llama.cpp production adapters stable.
6. Remote provider stable.
7. Spring AI adapter stable.
8. LangChain4j adapter stable.
9. Planner deterministic.
10. Plan serialization/lockfile работают.
11. Offline bundle работает.
12. Security review выполнен.
13. Observability integration присутствует.
14. Native resources корректно освобождаются.
15. No mandatory JInfer/DJL/Spring dependency exists in core.

---

# 109. Acceptance criteria — Java compatibility

## AC-JAVA-001

`cortexj-starter-java8` запускается на supported Java 8 distribution.

## AC-JAVA-002

`cortexj-starter-java25` использует Java 25 platform implementation.

## AC-JAVA-003

Business source code sample компилируется с минимальными/no changes между supported starters.

## AC-JAVA-004

Wrong starter/JVM combination возвращает actionable diagnostic.

## AC-JAVA-005

Java 25 features не попадают в shared Java 8 signatures.

---

# 110. Acceptance criteria — runtime independence

## AC-RUNTIME-001

Core test suite проходит без concrete runtime dependency.

## AC-RUNTIME-002

MVP application работает без JInfer.

## AC-RUNTIME-003

Удаление `cortexj-runtime-jinfer` не требует recompilation business code.

## AC-RUNTIME-004

Новый third-party runtime можно добавить без изменения planner source code.

## AC-RUNTIME-005

Planner видит runtime через descriptor/SPI.

---

# 111. Acceptance criteria — planner

- одинаковый input → одинаковый plan;
- rejected candidate имеет reason;
- hard constraint нельзя обойти score'ом;
- explicit runtime requirement respected;
- memory failure обнаруживается до load where possible;
- fallback ordered and explained;
- no provider-order dependence.

---

# 112. Acceptance criteria — model repository

- model revision resolved;
- corrupted artifact rejected;
- cache reuse;
- offline error clear;
- auth token not logged;
- mutable branch can be pinned.

---

# 113. Acceptance criteria — security

- path traversal blocked;
- checksum mismatch blocked;
- native artifact mismatch blocked;
- untrusted executable model code never auto-executed;
- credentials redacted;
- prompts not logged by default.

---

# 114. Major risks

## Risk 1 — Scope explosion

Попытка покрыть весь AI ecosystem превратит платформу в неподдерживаемый framework.

Mitigation: strict layer boundaries.

## Risk 2 — Planner becomes magical black box

Mitigation: explainability + deterministic policies.

## Risk 3 — Java 8 compatibility slows modern implementation

Mitigation: shared contract + LTS-specific modules.

## Risk 4 — Native matrix cost

Mitigation: provider-owned packaging and limited supported matrix per release.

## Risk 5 — Backend semantic mismatch

Одна модель в разных runtime может вести себя не полностью идентично.

Mitigation: capability/compatibility declarations and conformance tests.

## Risk 6 — Runtime providers duplicate model resolution

Mitigation: strict repository/artifact abstraction and provider contract.

## Risk 7 — Becoming another JInfer

Mitigation: do not implement tensor engine unless future strategic decision explicitly changes scope.

---

# 115. Key differentiation after competitor analysis

JInfer решает задачу:

```text
Execute AI models inside JVM
```

CortexJ решает задачу:

```text
Make AI model execution portable across JVM versions,
model artifacts, hardware and interchangeable runtimes.
```

Ключевые differentiators:

1. Java 8→25 LTS strategy;
2. multi-runtime abstraction;
3. runtime-independent model API;
4. Execution Planner;
5. explainable runtime selection;
6. artifact selection;
7. model portability;
8. legacy enterprise enablement;
9. runtime fallback;
10. no dependency on a single inference engine.

---

# 116. Why not one universal multi-release JAR

Не является рекомендуемой primary distribution strategy из-за сложности:

- debugging;
- native-image tooling;
- scanners;
- class loading;
- build reproducibility;
- IDE behavior;
- binary compatibility.

Используем shared artifacts + LTS-specific starters/modules.

---

# 117. Why not five independent libraries

Потому что это создаёт:

- duplicated fixes;
- divergent behavior;
- incompatible APIs;
- five release trains;
- backport burden.

Shared core является обязательным.

---

# 118. Repository layout

```text
/
├── pom.xml
├── README.md
├── LICENSE
├── SECURITY.md
├── CONTRIBUTING.md
├── CODE_OF_CONDUCT.md
│
├── bom/
├── api/
├── spi/
├── core/
├── model/
├── hub/
├── cache/
├── artifact/
├── conversion/
├── hardware/
├── planner/
├── streaming/
├── observability/
│
├── platform/
│   ├── java8/
│   ├── java11/
│   ├── java17/
│   ├── java21/
│   └── java25/
│
├── runtimes/
│   ├── onnx/
│   ├── llamacpp/
│   ├── remote-openai/
│   ├── jinfer/
│   └── djl/
│
├── repositories/
│   ├── local/
│   ├── huggingface/
│   └── http/
│
├── integrations/
│   ├── spring-ai/
│   └── langchain4j/
│
├── starters/
│   ├── java8/
│   ├── java11/
│   ├── java17/
│   ├── java21/
│   └── java25/
│
├── cli/
├── server/
├── tck/
├── benchmarks/
├── examples/
└── docs/
```

---

# 119. ADRs required before coding

Обязательные ADR:

1. ADR-001 Public API Java baseline.
2. ADR-002 LTS starter strategy.
3. ADR-003 RuntimeProvider SPI.
4. ADR-004 Execution Planner scoring.
5. ADR-005 Native binary packaging.
6. ADR-006 Streaming abstraction.
7. ADR-007 Model artifact identity.
8. ADR-008 Cache layout.
9. ADR-009 Hugging Face resolution.
10. ADR-010 ONNX provider architecture.
11. ADR-011 llama.cpp binding approach.
12. ADR-012 Framework integration boundary.
13. ADR-013 Security/trust model.
14. ADR-014 Model lockfile.
15. ADR-015 Runtime fallback semantics.

---

# 120. First technical spikes

До полноценной разработки выполнить 5 spikes.

## Spike A — Multi-JDK API

Проверить одну shared API библиотеку + starters на Java 8/11/17/21/25.

## Spike B — ONNX

Один embedding model через Java 8 и Java 21 paths.

## Spike C — llama.cpp

Один GGUF LLM через минимум Java 11/21/25.

## Spike D — Planner

Synthetic runtime candidates + deterministic selection/explanation.

## Spike E — HF model resolution

Model ID → revision → artifact list → local cache.

---

# 121. First end-to-end demonstration

Target demo:

```text
Qwen/supported GGUF model
      ↓
Hugging Face resolution
      ↓
Artifact inspection
      ↓
Hardware detection
      ↓
Runtime discovery
      ↓
Execution Planner
      ↓
llama.cpp selected
      ↓
model load
      ↓
streamed generation
```

Second demo:

```text
embedding model
      ↓
ONNX artifact
      ↓
ONNX Runtime
      ↓
embedding result
```

Third demo:

```text
same logical API
Java 8 starter
```

---

# 122. JInfer integration demonstration — later

После core independence proof:

```text
Java 25
+ cortexj-runtime-jinfer
      ↓
planner sees new runtime
      ↓
JInfer becomes candidate
```

Удаляем dependency:

```text
planner sees remaining runtimes
application still works
```

Этот тест обязателен для доказательства правильной архитектуры adapter'а.

---

# 123. Product messaging

Нельзя позиционировать как:

> Better JInfer.

Нельзя позиционировать как:

> Spring AI replacement.

Нельзя позиционировать как:

> PyTorch for Java.

Рекомендуемое positioning:

> **Portable AI execution for the JVM.**

или:

> **One JVM API. Any compatible model. The best available runtime.**

или:

> **AI runtime orchestration from Java 8 to Java 25+.**

---

# 124. Success metrics

## Adoption metrics

- downloads;
- runtime providers;
- third-party plugins;
- supported Java versions;
- projects using legacy starters;
- Spring AI integration users.

## Technical metrics

- planner success rate;
- plan explainability coverage;
- runtime TCK coverage;
- model compatibility matrix;
- load failure rate;
- native crash rate;
- cross-version API compatibility.

## Strategic metric

Количество production teams, для которых AI integration больше не требует отдельного Python service только из-за model execution tooling.

---

# 125. Future extensions

После v1:

- richer conversion graph;
- multimodal artifact planning;
- accelerator cost policies;
- Kubernetes-aware runtime placement;
- isolated native worker;
- distributed inference provider;
- model registry governance;
- model quality benchmarks;
- auto-benchmark runtime selection;
- enterprise policy server;
- declarative AI API;
- Kotlin-first adapter;
- Scala adapter;
- Android investigation where runtimes permit.

---

# 126. Future — benchmark-aware planner

Planner может дополнительно использовать local benchmark database:

```text
model family
runtime
hardware
quantization
latency
throughput
memory
```

Но benchmark learning не входит в MVP.

---

# 127. Future — isolated native execution

Некоторые native runtimes могут crash JVM.

Optional worker mode:

```text
Java Application
      ↓
CortexJ
      ↓
local worker process
      ↓
native runtime
```

Это позволяет crash isolation без изменения public model API.

---

# 128. Future — enterprise runtime policy

Организация может запретить:

- определённые runtimes;
- external downloads;
- unapproved licenses;
- unsigned native binaries;
- GPU execution;
- remote execution.

Planner применяет centrally supplied policy.

---

# 129. Glossary

**Model Source** — место, откуда logical model разрешается в artifacts.

**Model Artifact** — конкретный файл/набор файлов модели.

**Artifact Format** — GGUF, ONNX, Safetensors и т.д.

**Runtime Provider** — plugin, умеющий исполнить некоторый набор artifacts/tasks.

**Runtime** — конкретный execution engine instance.

**Capability** — функциональная/техническая возможность.

**Environment Profile** — характеристики JVM/OS/process.

**Hardware Profile** — CPU/GPU/RAM/accelerator данные.

**Execution Candidate** — допустимая комбинация model artifact + runtime + device + options.

**Execution Plan** — выбранный воспроизводимый путь исполнения.

**Fallback Plan** — альтернативные compatible execution candidates.

**Starter** — Maven dependency aggregator под конкретную LTS Java.

---

# 130. Research baseline

При разработке v2.0 необходимо регулярно перепроверять актуальное состояние следующих ecosystems:

- Quixotic AI / JInfer: https://qxotic.ai/
- Spring AI: https://docs.spring.io/spring-ai/reference/
- LangChain4j: https://docs.langchain4j.dev/
- ONNX Runtime Java: https://onnxruntime.ai/docs/get-started/with-java.html
- llama.cpp: https://github.com/ggml-org/llama.cpp
- DJL: https://djl.ai/
- Hugging Face model ecosystem: https://huggingface.co/docs
- Java release/JDK capabilities: OpenJDK/Oracle specifications.

External implementation details не должны становиться permanent assumptions без ADR.

---

# 131. Final architecture

```text
                         JVM APPLICATION
                               │
             ┌─────────────────┴─────────────────┐
             │                                   │
       Direct CortexJ API                  Spring AI / LC4J
             │                                   │
             └─────────────────┬─────────────────┘
                               │
                        CORTEXJ
                               │
            ┌──────────────────┼───────────────────┐
            │                  │                   │
       Model Resolver     Capability/Hardware  Diagnostics
            │                  │                   │
            └──────────────────┼───────────────────┘
                               │
                       EXECUTION PLANNER
                               │
                ┌──────────────┼───────────────┐
                │              │               │
             Candidate      Policies       Memory Model
                │              │               │
                └──────────────┼───────────────┘
                               │
                          Runtime SPI
                               │
        ┌───────────┬──────────┼───────────┬───────────┐
        │           │          │           │           │
       ONNX      llama.cpp    Remote       DJL       JInfer
        │           │          │           │           │
        └───────────┴──────────┼───────────┴───────────┘
                               │
                       CPU / GPU / NPU
```

Java platform layer:

```text
Shared API/Core
      │
      ├── Java 8 platform
      ├── Java 11 platform
      ├── Java 17 platform
      ├── Java 21 platform
      └── Java 25 platform
```

Distribution:

```text
cortexj-starter-java8
cortexj-starter-java11
cortexj-starter-java17
cortexj-starter-java21
cortexj-starter-java25
```

---

# 132. Final product contract

Пользователь должен иметь возможность сказать:

```java
CortexJ platform = CortexJ.builder().build();

ModelHandle model = platform.models()
        .load(ModelReference.of("company/model"));
```

и платформа должна ответить на все infrastructure questions:

```text
Где модель?
Какая revision?
Какие artifacts?
Что совместимо с текущей Java?
Какие runtimes установлены?
Что поддерживает текущий task?
Какое hardware доступно?
Какой artifact лучше?
Какой runtime лучше?
Хватит ли памяти?
Нужна ли конвертация?
Какой fallback?
Почему принято это решение?
```

При этом business code не должен знать:

```text
OrtSession
llama_context
JinferChatModel
DJL Predictor
CUDA native loader
```

если пользователь сам не решил перейти на expert/native API.

---

# 133. Final principle

Любое архитектурное решение проекта должно проверяться вопросом:

> **Помогает ли оно сделать AI execution переносимым между JVM versions, model artifacts, hardware и runtimes — без привязки пользователя к одному engine?**

Если нет, оно либо относится к optional plugin, либо находится вне scope core platform.

