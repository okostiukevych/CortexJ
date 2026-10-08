# CortexJ — техническая спецификация и ТЗ v3.0

> **Официальное название продукта:** **CortexJ**.
>
> **Долгосрочное позиционирование:** *CortexJ — AI development platform for the JVM, from experimentation to production.*
>
> **Первый продуктовый слой:** *CortexJ Runtime — portable AI execution and runtime orchestration for the JVM.*
>
> **Maven namespace:** `io.cortexj` / `cortexj-*`.
>
> **Статус:** Architecture/Product Specification — Draft for implementation.
>
> **Дата:** 2026-09-28.
>
> **Главное изменение относительно v2.0:** CortexJ больше не рассматривается только как runtime orchestration/model portability platform. Runtime остаётся первым и обязательным продуктовым слоем, однако вся архитектура и roadmap теперь проектируются как постепенное построение **полного AI lifecycle для JVM**: Runtime → Lab/Notebook → Data → Experiments → Fine-tuning/PEFT → PyTorch interoperability → General Training → Tensor API → Autograd → Architecture Experimentation → Distributed Training → Research/Academic ecosystem.
>
> **Ключевое ограничение:** долгосрочное расширение не должно разрушать простоту Runtime, его multi-JDK compatibility и независимость от конкретных engines/frameworks.

---

# 0. Executive decision record

В v3.0 зафиксированы следующие решения.

0. **Официальное название платформы — CortexJ.**
1. CortexJ развивается как **единая AI development platform для JVM**, но поставляется и развивается по независимым слоям.
2. **CortexJ Runtime — P0 и первый production product.** Его можно завершить, выпустить и использовать независимо от будущих Lab/Train/Research модулей.
3. Runtime не является новым inference engine. Он отвечает за model discovery, portability, runtime abstraction, hardware/capability discovery, execution planning, lifecycle, diagnostics и единый API.
4. Runtime обязан работать без JInfer, Spring AI, LangChain4j, DJL или любого одного конкретного engine/framework.
5. Минимальные обязательные execution paths Runtime: ONNX Runtime, llama.cpp и OpenAI-compatible remote runtime.
6. **JInfer — optional Java 25+ RuntimeProvider**, если его публичный API и лицензия совместимы с требованиями CortexJ. Его отсутствие не влияет на работоспособность платформы.
7. Spring AI и LangChain4j являются integration layers поверх CortexJ Runtime, а не зависимостями core.
8. Runtime поддерживает LTS-линии Java 8/11/17/21/25 через shared API/core + LTS-specific starters/modules.
9. Современные исследовательские подсистемы CortexJ не обязаны поддерживать Java 8. Compatibility задаётся **на уровне продукта/модуля**, а не всей платформы целиком.
10. Рекомендуемые baselines:
    - CortexJ Runtime API/Core: Java 8+;
    - CortexJ Lab/Data/Experiments: Java 17+;
    - CortexJ Train: Java 17+ с optimized tier на 21/25;
    - CortexJ Tensor/Autograd/Research: Java 21+; preferred Java 25 для advanced native/FFM возможностей.
11. CortexJ не переписывает существующую экосистему без необходимости. Принцип: **integrate first, replace only when a clear technical gap remains**.
12. Поэтому DJL, Tribuo, Arrow/Tablesaw, Jupyter Java kernels, LibTorch/native backends и другие зрелые компоненты могут использоваться как providers/adapters, но public API CortexJ не должен быть привязан к ним.
13. PyTorch interoperability появляется **раньше**, чем попытка построить собственный полноценный tensor/autograd engine.
14. Fine-tuning/LoRA/PEFT появляется **раньше general training**, потому что это намного более частый production use case.
15. Собственный Tensor API и Autograd являются долгосрочным foundation для независимого research, но не блокируют Runtime/Lab/Train.
16. P0–P11 являются официальными стратегическими приоритетами платформы:

```text
P0  Runtime / Inference
P1  Lab / Notebook
P2  Data
P3  Experiments / Reproducibility
P4  Fine-tuning / PEFT
P5  PyTorch Interoperability
P6  General Training
P7  Tensor API
P8  Autograd
P9  Architecture Experimentation
P10 Distributed Training
P11 Research / Academic Ecosystem
```

17. Общая продуктовая цель:

> **Убрать AI ecosystem gap как причину не выбирать JVM для production AI-продукта и постепенно сделать JVM полноценной средой для всего AI lifecycle.**

18. Нельзя жертвовать production-quality Runtime ради амбиции Research: каждый слой имеет собственные API, compatibility matrix, release cadence и Definition of Done.

---

# 1. Product definition

## 1.1. CortexJ Platform

CortexJ — модульная AI development platform для JVM, покрывающая путь от использования готовой модели до экспериментирования, fine-tuning, training и исследования новых архитектур.

Долгосрочная модель продукта:

```text
CortexJ Platform
│
├── CortexJ Runtime
│   └── production inference, model portability, execution planning
│
├── CortexJ Lab
│   └── notebooks, interactive experiments, visualization
│
├── CortexJ Data
│   └── datasets, frames, Arrow/Parquet/JDBC/stream adapters
│
├── CortexJ Experiments
│   └── reproducibility, metrics, artifacts, provenance
│
├── CortexJ Train
│   └── PEFT/fine-tuning, trainers, optimizers, distributed hooks
│
├── CortexJ Tensor
│   └── tensor/device/dtype/ops abstraction
│
└── CortexJ Research
    └── autograd, custom modules, architecture experimentation, academic workflows
```

## 1.2. Первый продукт: CortexJ Runtime

CortexJ Runtime — multi-runtime AI orchestration и model portability layer, позволяющий приложению единообразно:

- ссылаться на AI-модель;
- находить model artifacts;
- анализировать совместимость;
- определять hardware/JVM capabilities;
- выбирать execution engine;
- выбирать artifact/quantization/device;
- планировать conversion/fallback;
- выполнять inference;
- переключать runtime без изменения business code;
- объяснять принятое execution decision;
- работать на нескольких LTS-поколениях Java.

## 1.3. Long-term product statement

> **CortexJ — the AI development platform for the JVM, from experimentation to production.**

Для Runtime отдельно:

> **CortexJ Runtime — one portable AI execution layer for the JVM.**

## 1.4. Что должен ощущать production developer

```java
CortexJ platform = CortexJ.builder().build();

ModelHandle model = platform.models()
        .load(ModelReference.of("Qwen/Qwen3-8B"));
```

Он не обязан заранее знать ONNX/llama.cpp/JInfer/DJL/native details.

## 1.5. Что должен ощущать AI developer в будущем

```java
Dataset dataset = CortexJ.data().readParquet("train.parquet");

TrainingJob job = CortexJ.train()
        .model("Qwen/Qwen3-8B")
        .dataset(dataset)
        .strategy(TrainingStrategy.LORA)
        .device(Device.AUTO)
        .start();
```

А в Research tier:

```java
Tensor x = tensors.variable(...);
Tensor y = model.forward(x).mean();
y.backward();
```

Все уровни должны использовать совместимые понятия ModelReference, Dataset, Device, Artifact, Experiment и Provenance, но не обязаны зависеть друг от друга runtime-wise.

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

## G-001. Unified production model API
Business code не зависит от конкретного execution engine.

## G-002. Runtime independence
Любой inference engine подключается через SPI.

## G-003. Multi-Java Runtime support
CortexJ Runtime поддерживает Java 8/11/17/21/25 без пяти fork'ов продукта.

## G-004. Modern JVM optimization
Java 21/25 получают дополнительные capabilities без изменения application code.

## G-005. Legacy enablement
Существующие Java 8/11 приложения получают practical AI path без обязательного Python sidecar.

## G-006. No mandatory Python for production inference
Runtime path для supported models не требует установленного Python.

## G-007. Explainable planning
AUTO decisions имеют reason codes, diagnostics и воспроизводимый ExecutionPlan.

## G-008. Deterministic deployment
При фиксированных revision/runtime/platform/hardware/configuration план воспроизводим.

## G-009. Progressive disclosure
Простой default path + полностью управляемый expert path.

## G-010. Ecosystem interoperability
Spring AI, LangChain4j и другие frameworks используют CortexJ через adapters.

## G-011. Full AI lifecycle over time
Платформа должна иметь архитектурный путь от inference к experiments, fine-tuning, training и research без переписывания Runtime core.

## G-012. Interoperate before reimplement
Зрелые ecosystems подключаются через providers/adapters до решения писать собственный engine.

## G-013. PyTorch compatibility before PyTorch replacement
Checkpoint/model/adapter interoperability имеет более высокий приоритет, чем собственный full deep-learning engine.

## G-014. Reproducibility as a platform feature
Model revision, dataset identity, hyperparameters, hardware, runtime, seeds и artifacts должны отслеживаться единообразно между Lab/Train/Runtime.

## G-015. One conceptual model, different capability tiers
`ModelReference`, `Device`, `Artifact`, `Dataset`, `Experiment`, `ExecutionPlan` и related concepts должны быть совместимы между подсистемами, но implementation может отличаться.

## G-016. Production path must remain lightweight
Пользователь, которому нужен только inference, не должен получать dependencies Lab/Data/Training/Tensor/Autograd.

---

# 5. Scope boundaries and phase-specific non-goals

В v3.0 запрещено использовать один глобальный список «никогда не делаем». Ограничения задаются по продуктовым фазам.

## 5.1. CortexJ Runtime 1.x — non-goals

Runtime 1.x НЕ обязан включать:

1. собственный general-purpose tensor engine;
2. собственный CUDA compiler;
3. собственный autograd;
4. full neural-network training;
5. distributed training;
6. замену PyTorch/JAX;
7. замену Spring AI/LangChain4j;
8. полный RAG framework;
9. собственную vector database;
10. arbitrary Python code execution;
11. automatic execution любой неизвестной HF architecture;
12. обязательный JInfer/DJL dependency.

## 5.2. CortexJ Platform — долгосрочно допустимые области

Следующие области **не являются non-goals всей платформы** и официально входят в roadmap:

- interactive notebooks;
- data science workflows;
- experiment tracking;
- fine-tuning / LoRA / QLoRA / PEFT;
- PyTorch interoperability;
- general training;
- Tensor API;
- autograd;
- custom architecture authoring;
- distributed training;
- academic/research tooling.

## 5.3. Permanent non-goals unless separately approved by ADR

- собственный GPU driver;
- собственная JVM;
- собственный CUDA replacement language только ради CortexJ;
- собственный cloud hyperscaler;
- закрытый proprietary model format как основной путь;
- forced lock-in на один runtime/provider;
- скрытый запуск arbitrary repository code;
- API, требующий Spring для core use cases.

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

## 7.1. Compatibility is product-specific

CortexJ больше не использует правило «вся платформа обязана запускаться на Java 8». Java compatibility определяется по модулю.

| Подсистема | Minimum target | Recommended | Причина |
|---|---:|---:|---|
| Runtime API/Core | Java 8 | Java 21/25 | legacy production adoption |
| Runtime starters | 8/11/17/21/25 | matching LTS | optimized per LTS |
| Spring AI integration | ecosystem-dependent | modern LTS | зависит от Spring baseline |
| Lab/Notebook | Java 17 | Java 21+ | interactive tooling |
| Data | Java 17 | Java 21+ | modern Arrow/data integrations |
| Experiments | Java 17 | Java 21+ | reproducibility tooling |
| Train | Java 17 | Java 21/25 | concurrency/native backends |
| PyTorch interop | backend-dependent | Java 21/25 | native bridge requirements |
| Tensor | Java 21 | Java 25 | modern memory/native capabilities |
| Autograd | Java 21 | Java 25 | Tensor dependency |
| Research | Java 21 | Java 25 | new architecture experimentation |
| Distributed Training | Java 21 | Java 25 | modern networking/concurrency/native |

## 7.2. Runtime uses one platform, not five forks

```text
Shared Runtime API/Core
      │
      ├── Java 8 platform
      ├── Java 11 platform
      ├── Java 17 platform
      ├── Java 21 platform
      └── Java 25 platform
```

LTS-specific starters собирают правильный dependency graph, но public Runtime API максимально одинаков.

## 7.3. Research stack is allowed to move faster

Tensor/Autograd/Research не обязаны сохранять bytecode compatibility с Java 8. Это сознательное решение, чтобы legacy support Runtime не блокировал использование современных JVM features.

## 7.4. Migration guarantee for Runtime

Переход приложения между supported Runtime starters должен требовать изменения dependency/configuration, но не business logic при использовании stable API.

## 7.5. Capability-first internals

Внутренние решения принимаются по capabilities (`FFM`, virtual threads, vector support, native access, GPU backend и т.д.), а версия JDK используется как distribution/compatibility boundary.

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

JInfer рассматривается как optional provider, а не фундамент CortexJ.

Требования:

- отдельный artifact `cortexj-runtime-jinfer`;
- baseline определяется фактической JInfer release line (для текущего integration target — Java 25+);
- core не имеет compile dependency на JInfer;
- provider adapter инкапсулирует `Jinfer*` types;
- planner видит JInfer только когда provider присутствует и environment compatible;
- удаление dependency не ломает CortexJ;
- Runtime Provider TCK обязателен;
- version support matrix публикуется отдельно.

Java 8/11/17/21 starters не обязаны включать JInfer.

## 19.2. DJL provider

DJL является optional multi-engine provider. Он особенно важен как потенциальный мост к будущему Train/Tensor ecosystem, однако Runtime Core не зависит от DJL.

## 19.3. Future providers

- TensorRT;
- OpenVINO;
- vendor NPU runtimes;
- native JVM engines;
- isolated worker runtimes;
- future CortexJ Tensor runtime.

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

# 104. Runtime MVP explicitly excludes

CortexJ Runtime MVP не включает:

- own tensor engine;
- mandatory JInfer;
- arbitrary Safetensors direct execution;
- training;
- fine-tuning;
- notebook environment;
- dataframe engine;
- RAG engine;
- vector database;
- agent framework;
- automatic support for arbitrary HF custom Python models.

Важно: training/fine-tuning/notebooks/data являются **future CortexJ Platform tracks**, а не permanent non-goals.

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

# 106. Platform priority roadmap (P0–P11)

Ниже — продуктовый порядок развития. Milestones внутри каждой priority могут выпускаться независимо.

## P0 — CortexJ Runtime / Inference

Цель: production AI без обязательного Python.

Deliverables:

- shared Runtime API/Core/SPI;
- ModelReference/Descriptor/Artifact;
- HF/local repository resolution;
- ONNX Runtime provider;
- llama.cpp provider;
- OpenAI-compatible remote provider;
- optional JInfer/DJL providers;
- Execution Planner;
- hardware/capability discovery;
- cache, lockfile, offline bundle;
- text generation, embeddings, multimodal where backend supports;
- Java 8/11/17/21/25 starters;
- Spring AI/LangChain4j adapters;
- observability/security/diagnostics.

Exit condition: Runtime v1.0 Definition of Done выполнен.

## P1 — CortexJ Lab / Notebook

Цель: интерактивный JVM AI workflow без обязательного перехода в Python notebook.

Deliverables:

- Jupyter-compatible Java kernel integration;
- dependency loading ergonomics;
- rich display contracts;
- table/image/chart/model-plan renderers;
- Runtime integration;
- reproducible notebook environment descriptor;
- export notebook experiment → Java project skeleton.

## P2 — CortexJ Data

Цель: единый data abstraction для Lab/Train/Evaluation.

Deliverables:

- `Dataset`, `Frame`, `Column`, `Batch`, `DataSource` contracts;
- CSV/JSON/Parquet/JDBC/Arrow adapters;
- lazy/streaming dataset where practical;
- schema/type system;
- transforms, split/shuffle/sample;
- connectors to Tribuo/Tablesaw/Arrow/DJL where useful;
- dataset fingerprinting/provenance.

## P3 — CortexJ Experiments / Reproducibility

Цель: каждый experiment воспроизводим.

Deliverables:

- Experiment lifecycle;
- parameters/metrics/artifacts;
- model revision and dataset hash capture;
- runtime/hardware/JDK capture;
- seeds/environment capture;
- local experiment store;
- pluggable external trackers;
- compare/rank runs;
- export run manifest.

## P4 — Fine-tuning / PEFT

Цель: practically useful model adaptation before full general training.

Deliverables:

- LoRA;
- QLoRA where backend supports;
- PEFT adapter abstractions;
- supervised fine-tuning pipeline;
- tokenizer/dataset preprocessing;
- checkpoints;
- resume;
- evaluation hooks;
- adapter export/import;
- merged/unmerged deployment into Runtime.

## P5 — PyTorch interoperability

Цель: использовать Python/PyTorch ecosystem без требования писать весь production stack на Python.

Deliverables:

- Safetensors/checkpoint interoperability;
- LoRA/PEFT artifacts;
- ONNX/export compatibility;
- optional LibTorch/native bridge where technically stable;
- dtype/device mapping;
- model metadata mapping;
- explicit unsupported-feature diagnostics.

## P6 — General Training

Цель: единый JVM training API поверх нескольких engines.

Deliverables:

- Trainer;
- Module/Model training contract;
- Optimizer;
- Loss;
- Scheduler;
- metrics;
- dataloading/batching;
- checkpoint lifecycle;
- mixed precision abstraction;
- backend providers (initially integrate mature engines before own engine).

## P7 — CortexJ Tensor

Цель: engine-neutral tensor programming model.

Deliverables:

- Tensor;
- Shape;
- DType;
- Device;
- memory/layout;
- core math ops;
- broadcasting;
- views/slices;
- serialization;
- backend SPI;
- CPU/reference backend;
- adapters to accelerated engines.

## P8 — Autograd

Цель: differentiable computation on JVM.

Deliverables:

- computational graph/tape abstraction;
- gradient tracking;
- backward;
- detach/no-grad;
- custom gradient rules;
- gradient accumulation;
- correctness/differential tests against reference implementations.

## P9 — Architecture Experimentation

Цель: создание новых neural architectures непосредственно на JVM.

Deliverables:

- Module API;
- parameter registration;
- reusable layers;
- custom attention blocks;
- initialization;
- model serialization;
- graph inspection;
- custom op extension mechanism;
- notebook integration.

## P10 — Distributed Training

Цель: scale-out training.

Deliverables:

- multi-GPU;
- multi-node;
- data parallel;
- gradient synchronization;
- checkpoint sharding;
- later tensor/pipeline parallel;
- fault handling;
- topology-aware planner.

## P11 — Research / Academic Ecosystem

Цель: end-to-end JVM environment for reproducible AI research and education.

Deliverables:

- paper/project templates;
- benchmark suites;
- datasets registry adapters;
- experiment bundles;
- reproducibility manifests;
- model cards/dataset cards;
- citation metadata;
- education/tutorial environment;
- reference implementations of selected architectures.

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

## Risk 7 — Prematurely becoming another inference/tensor engine

Mitigation: Runtime P0 не реализует собственный tensor/inference engine без измеримого gap. Собственный Tensor/Autograd допускается только начиная с P7/P8 как отдельный product track с отдельными ADR, benchmarks и dependency boundary; он не должен становиться обязательной реализацией Runtime.

---

# 115. Key differentiation after competitor analysis

## 115.1. CortexJ Runtime vs JInfer

JInfer решает преимущественно задачу:

```text
Execute AI models inside a modern JVM
```

CortexJ Runtime решает:

```text
Make AI execution portable across JVM versions,
model artifacts, hardware and interchangeable runtimes.
```

Runtime differentiators:

1. Java 8→25 LTS strategy;
2. multi-runtime abstraction;
3. runtime-independent model API;
4. Execution Planner;
5. explainable runtime selection;
6. artifact selection/conversion planning;
7. model portability;
8. legacy enterprise enablement;
9. fallback/policy model;
10. no dependency on a single inference engine.

## 115.2. Long-term CortexJ differentiation

CortexJ Platform идёт выше Runtime и связывает единым lifecycle:

```text
Lab → Data → Experiments → Fine-tuning → Training
    → Tensor/Autograd → Research → Runtime deployment
```

JInfer при этом может оставаться одним из Runtime providers. CortexJ не обязана вытеснять его или повторять его engine internals.

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

# 118. Repository layout — Runtime foundation

P0 Runtime repository foundation:

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
├── repositories/
├── integrations/
├── starters/
├── cli/
├── server/
├── tck/
├── benchmarks/
├── examples/
└── docs/
```

Полная multi-track структура v3.0 приведена в §135. Runtime foundation не зависит от future Lab/Train/Tensor tracks.

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

CortexJ имеет два уровня messaging.

## 123.1. Current/near-term product

> **CortexJ Runtime — portable AI execution for the JVM.**

или:

> **One JVM API. Compatible models. The best available runtime.**

или:

> **Production AI from Java 8 to Java 25+, without mandatory Python.**

## 123.2. Long-term platform

> **CortexJ — the AI development platform for the JVM, from experimentation to production.**

Нельзя обещать до реализации:

- «полная замена PyTorch»;
- «любая Hugging Face model работает автоматически»;
- «research parity with Python»;
- «zero Python для всех research workflows».

Правильная стратегия — публиковать capability matrix по каждому release.

---

# 124. Success metrics and strategic KPIs

## 124.1. Runtime developer experience

Target metrics:

- time-to-first-local-response: ≤ 10 минут, mature target ≤ 5 минут;
- starter dependencies для basic use case: 1;
- hello-world application code: < 10 significant LOC;
- mandatory Python process: 0;
- mandatory AI sidecar для embedded path: 0;
- runtime migration: 0 business-code changes;
- supported hardware migration: 0 business-code changes.

## 124.2. Runtime performance

- framework latency overhead vs direct backend: target < 2%;
- throughput degradation: target < 5%;
- framework memory overhead without model: target < 100 MB;
- runtime selection overhead на hot inference path: effectively zero after planning/cache;
- silent fallback count: 0.

## 124.3. Compatibility

- planner valid-plan rate on curated compatibility matrix: ≥ 99%;
- popular-model benchmark coverage Runtime v1: ≥ 80%;
- mature Runtime target: ≥ 95%;
- median support lag for high-demand models with compatible artifacts: target < 7 days;
- compatible artifact available but unexplained load failure: < 1% benchmark cases.

## 124.4. Lab/Data KPIs

- notebook → first model inference: ≤ 5 minutes after environment setup;
- supported core tabular IO: CSV/JSON/Parquet/JDBC/Arrow;
- experiment notebook export retains model/dataset revision metadata: 100%;
- no mandatory Python kernel for supported Lab workflows.

## 124.5. Training KPIs

- deterministic resume from checkpoint for supported backends;
- model/optimizer/dataset/hyperparameter provenance capture: 100% of managed training runs;
- LoRA adapter produced by CortexJ deployable by CortexJ Runtime without manual format surgery;
- backend changes do not alter user-level training orchestration API beyond declared capability gaps.

## 124.6. Research KPIs

После P7–P9:

- gradient differential tests against trusted reference: defined tolerance per dtype/op;
- core tensor op correctness suite coverage ≥ 99% for supported op matrix;
- custom Module can be authored without backend-specific application code;
- notebook → train → export → Runtime path demonstrated end-to-end.

## 124.7. Strategic metric

Количество JVM teams, для которых AI integration больше не требует выбора Python только из-за отсутствия model/runtime/training tooling.

CortexJ не ставит формальный KPI «догнать Python по всем AI repositories». Более корректная цель — сделать Java/JVM **равноправным выбором для production AI-enabled applications**, а затем последовательно сокращать разрыв в experimentation/training/research.

---

# 125. Platform expansion principles

1. Runtime всегда usable standalone.
2. Lab зависит от public Runtime/Data APIs, но Runtime не зависит от Lab.
3. Train может зависеть от Data/Experiments/Tensor abstractions, но Runtime не зависит от Train.
4. Tensor/Autograd не должны становиться transitive dependencies обычного inference application без явного запроса.
5. Integration-first: зрелый backend подключается provider'ом.
6. Own-engine work допускается только при измеримом gap в portability/performance/DX.
7. Каждый новый track имеет собственную capability matrix.
8. Cross-track artifacts имеют versioned schemas.
9. Reproducibility/provenance проходят через весь lifecycle.
10. Security trust boundaries Runtime и Research различаются: Research может разрешать user-defined code локально, Runtime repository loading по умолчанию не исполняет arbitrary code.

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

При разработке v3.0 необходимо регулярно перепроверять актуальное состояние следующих ecosystems:

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
                              CORTEXJ PLATFORM
                                     │
          ┌──────────────────────────┼──────────────────────────┐
          │                          │                          │
          ▼                          ▼                          ▼
    CORTEXJ LAB                CORTEXJ TRAIN              CORTEXJ RUNTIME
    Notebook/UI                Fine-tune/Train            Production AI
          │                          │                          │
    ┌─────┴─────┐             ┌──────┴──────┐             ┌────┴─────────┐
    │           │             │             │             │              │
  Data     Experiments      Trainer      Optimizer     Model API     Planner
    │           │             │             │             │              │
    └───────────┴─────────────┼─────────────┴─────────────┼──────────────┘
                              │                           │
                        CORTEXJ TENSOR                    │
                              │                           │
                           AUTOGRAD                       │
                              │                           │
              ┌───────────────┼───────────────┐           │
              │               │               │           │
            CPU          Accelerators      External       │
                           backends         engines        │
              │               │               │           │
              └───────────────┴───────┬───────┴───────────┘
                                      │
                              MODEL / ARTIFACT HUB
```

Runtime internal architecture remains:

```text
JVM Application / Spring AI / LangChain4j
                  │
               CortexJ
                  │
       Model/Artifact Resolver
                  │
   Capability + Hardware Discovery
                  │
          Execution Planner
                  │
             Runtime SPI
                  │
   ┌──────────────┼──────────────┐
   │              │              │
 ONNX         llama.cpp        Remote
   │              │              │
   ├──────── optional providers ─┤
   │         DJL / JInfer / ...  │
   └──────────────┼──────────────┘
                  │
             CPU/GPU/NPU
```

Java compatibility layers are independent per product track, with Runtime retaining Java 8→25 LTS coverage and modern research modules targeting newer JDKs.

---

# 132. Final product contract

## 132.1. Runtime contract

Пользователь должен иметь возможность написать:

```java
CortexJ platform = CortexJ.builder().build();

ModelHandle model = platform.models()
        .load(ModelReference.of("company/model"));
```

и CortexJ отвечает на infrastructure questions: source/revision/artifacts/runtime/device/memory/conversion/fallback/reasoning.

## 132.2. Lab contract

Пользователь должен интерактивно исследовать данные и модели без перехода в другой язык для basic supported workflows.

## 132.3. Train contract

Fine-tuning/training code управляет intent (`model`, `dataset`, `strategy`, `optimizer`, `device=AUTO`), а backend-specific details изолированы provider'ами настолько, насколько это технически возможно.

## 132.4. Research contract

После появления Tensor/Autograd пользователь может определять custom differentiable Modules и architectures на JVM, запускать experiment, обучать, экспортировать artifact и передавать его в CortexJ Runtime.

## 132.5. Interoperability contract

CortexJ обязан уметь жить в мире, где model creation остаётся Python/PyTorch-first. Импорт/export и artifact compatibility являются first-class feature, а не временным workaround.

---

# 133. Final principle

Каждое решение CortexJ проверяется четырьмя вопросами:

1. **Уменьшает ли оно необходимость менять язык только ради AI capability?**
2. **Сохраняет ли оно portability между model artifacts, runtimes, hardware и JVM versions?**
3. **Можно ли подключить зрелую внешнюю реализацию через SPI вместо преждевременного переписывания?**
4. **Не заставляет ли новый research/training functionality платить complexity cost пользователя, которому нужен только Runtime?**

Если хотя бы один ответ отрицательный, решение требует отдельного ADR и доказательства своей ценности.

---

---

# 134. CortexJ product tracks and dependency boundaries

## 134.1. Track graph

```text
                         cortexj-bom
                             │
       ┌─────────────────────┼──────────────────────┐
       │                     │                      │
   Runtime Track          Lab Track             Train Track
       │                     │                      │
       │                  Data Track            Experiments
       │                     │                      │
       └──────────────┬──────┴────────────┬─────────┘
                      │                   │
                 Shared Model        Tensor Track
                 Metadata/API             │
                                      Autograd
                                          │
                                       Research
```

## 134.2. Dependency constraints

- `runtime/*` MUST NOT depend on `lab/*`, `train/*`, `tensor/*`, `research/*`.
- `lab/*` MAY depend on Runtime API, Data API, Experiments API.
- `train/*` MAY depend on Data/Experiments/Tensor API, but backend-specific engine dependencies remain optional.
- `tensor-api` MUST NOT depend on a concrete accelerator backend.
- `autograd` depends on Tensor API, not on Runtime.
- `research` may depend on Tensor/Autograd/Train/Lab.
- shared `model-metadata` and `provenance` contracts must remain lightweight.

---

# 135. Expanded Maven module architecture

```text
cortexj-bom
│
├── cortexj-api
├── cortexj-core
├── cortexj-model-metadata
├── cortexj-provenance
│
├── runtime/
│   ├── cortexj-runtime-spi
│   ├── cortexj-runtime-core
│   ├── cortexj-runtime-onnx
│   ├── cortexj-runtime-llamacpp
│   ├── cortexj-runtime-remote-openai
│   ├── cortexj-runtime-jinfer
│   └── cortexj-runtime-djl
│
├── platform/
│   ├── cortexj-java8
│   ├── cortexj-java11
│   ├── cortexj-java17
│   ├── cortexj-java21
│   └── cortexj-java25
│
├── lab/
│   ├── cortexj-lab-api
│   ├── cortexj-notebook
│   ├── cortexj-display
│   └── cortexj-charts
│
├── data/
│   ├── cortexj-data-api
│   ├── cortexj-data-core
│   ├── cortexj-data-arrow
│   ├── cortexj-data-parquet
│   ├── cortexj-data-jdbc
│   ├── cortexj-data-tablesaw
│   └── cortexj-data-tribuo
│
├── experiments/
│   ├── cortexj-experiments-api
│   ├── cortexj-experiments-local
│   └── cortexj-experiments-otel
│
├── train/
│   ├── cortexj-train-api
│   ├── cortexj-train-core
│   ├── cortexj-train-peft
│   ├── cortexj-train-djl
│   ├── cortexj-train-pytorch
│   └── cortexj-train-distributed
│
├── tensor/
│   ├── cortexj-tensor-api
│   ├── cortexj-tensor-cpu
│   ├── cortexj-tensor-native
│   ├── cortexj-tensor-cuda
│   └── cortexj-autograd
│
├── research/
│   ├── cortexj-nn
│   ├── cortexj-research
│   └── cortexj-benchmarks
│
├── integrations/
│   ├── cortexj-spring-ai
│   ├── cortexj-langchain4j
│   └── cortexj-kotlin
│
├── cli/
├── server/
├── tck/
├── benchmarks/
└── examples/
```

Имена future artifacts являются design targets и могут быть уточнены ADR до публикации.

---

# 136. CortexJ Lab specification

## 136.1. Goals

- interactive Java-first AI experimentation;
- zero boilerplate model loading;
- rich rendering of tables/images/charts/plans/metrics;
- seamless transition notebook → production source;
- reproducibility metadata by default.

## 136.2. Notebook kernel strategy

CortexJ не обязан писать Jupyter protocol implementation с нуля. Предпочтение:

1. adapter к зрелому Java/JShell kernel;
2. CortexJ-specific bootstrap/magics/extensions;
3. собственный kernel только если integration limitations доказаны spike'ом.

## 136.3. Required notebook capabilities

- Maven dependency injection/bootstrap;
- async/stream output rendering;
- table paging;
- image/audio display;
- chart rendering;
- ExecutionPlan visualization;
- training metrics live view;
- cancellation;
- reproducible environment descriptor;
- export cells to Maven project/source skeleton.

## 136.4. Example

```java
var data = CortexJ.data().readCsv("reviews.csv");
data.head(20);

var model = CortexJ.models().load("Qwen/Qwen3-8B");
model.generate("Summarize the dominant complaints");
```

---

# 137. CortexJ Data specification

## 137.1. Core abstractions

```java
public interface Dataset<T> { ... }
public interface Frame { ... }
public interface Column<T> { ... }
public interface Batch<T> { ... }
public interface DataSource<T> { ... }
```

## 137.2. Requirements

- immutable-by-default transformations where practical;
- streaming/lazy datasets for large corpora;
- deterministic train/validation/test split with seed;
- shuffle/sample/batch/window;
- typed schema;
- missing value semantics;
- Arrow interop;
- zero/low-copy paths where backend supports;
- dataset fingerprint;
- provenance metadata.

## 137.3. Initial adapters

- CSV;
- JSON/JSONL;
- Parquet;
- Apache Arrow;
- JDBC;
- filesystem corpus;
- Hugging Face dataset export formats where practical;
- Tablesaw adapter;
- Tribuo adapter;
- DJL dataset adapter.

---

# 138. Experiment and provenance subsystem

## 138.1. Experiment API

```java
try (Experiment experiment = CortexJ.experiments().start("qwen-lora")) {
    experiment.parameter("learningRate", 2e-4);
    experiment.parameter("epochs", 3);
    experiment.metric("validation.loss", 0.42);
    experiment.artifact("adapter", adapterPath);
}
```

## 138.2. Automatic capture

Managed jobs automatically capture:

- CortexJ version;
- JDK vendor/version;
- OS/architecture;
- CPU/GPU/accelerator identity;
- runtime/backend versions;
- model ID + immutable revision;
- dataset fingerprint;
- tokenizer/processor revisions;
- hyperparameters;
- random seeds;
- source/git metadata when available and explicitly enabled;
- output artifact hashes.

## 138.3. Storage SPI

```java
public interface ExperimentStore { ... }
```

Initial provider: local filesystem/SQLite-like local store. Future adapters may target external experiment trackers.

---

# 139. Fine-tuning and PEFT specification

## 139.1. Why PEFT first

Full pretraining is expensive and rarely the first requirement of a JVM product team. LoRA/QLoRA/adapter tuning delivers much higher practical value earlier.

## 139.2. High-level API target

```java
TrainingJob job = CortexJ.train()
        .model("Qwen/Qwen3-8B")
        .dataset(dataset)
        .strategy(TrainingStrategy.LORA)
        .epochs(3)
        .learningRate(2e-4)
        .device(Device.AUTO)
        .start();
```

## 139.3. Required behavior

- validate model/backend/strategy compatibility before allocating major resources;
- explicit memory estimate;
- checkpoint + resume;
- training/eval split;
- periodic evaluation;
- early stopping policy plugin;
- output adapter metadata;
- export compatible artifact;
- direct hand-off to Runtime deployment.

## 139.4. Backend strategy

Initial versions SHOULD wrap proven training engines rather than own kernels. Backend SPI decides whether DJL/PyTorch/native/remote trainer performs work.

---

# 140. PyTorch interoperability specification

## 140.1. Objective

CortexJ must coexist with the dominant PyTorch-first model creation ecosystem.

## 140.2. Artifact interoperability

Priority formats/contracts:

- Safetensors;
- ONNX;
- tokenizer artifacts;
- model config metadata;
- LoRA/PEFT adapters;
- standard quantized artifacts where applicable;
- checkpoint metadata mapping.

## 140.3. Native interoperability

Optional LibTorch/native bridge may be introduced when:

- ABI/version strategy is sustainable;
- supported operations can be stated precisely;
- native distribution/security model is acceptable;
- performance justifies complexity.

## 140.4. No fake compatibility

Unsupported custom ops, graph semantics or Python-only model code MUST produce explicit diagnostics rather than silent degradation.

---

# 141. General Training API

## 141.1. Public abstractions

```java
Trainer
TrainingPlan
Optimizer
LossFunction
LearningRateSchedule
CheckpointPolicy
EvaluationPolicy
MixedPrecisionPolicy
TrainingBackend
```

## 141.2. Example

```java
Trainer trainer = CortexJ.trainer()
        .model(model)
        .optimizer(AdamW.builder().learningRate(1e-3).build())
        .loss(Loss.crossEntropy())
        .device(Device.AUTO)
        .build();

TrainingResult result = trainer.fit(dataset);
```

## 141.3. Requirements

- synchronous + async job lifecycle;
- cancellation;
- checkpoint/resume;
- metrics stream;
- validation hooks;
- mixed precision capability negotiation;
- device/backend planning;
- memory estimates;
- reproducibility manifest;
- portable output artifact where model architecture permits.

---

# 142. Tensor API

## 142.1. Core model

```java
Tensor
Shape
DType
Device
Layout
TensorBackend
TensorStorage
```

## 142.2. API principles

- immutable metadata;
- clear ownership/lifecycle for native memory;
- explicit dtype/device transfer;
- broadcasting semantics documented and tested;
- lazy/eager behavior never ambiguous;
- backend-specific escape hatch available but isolated.

## 142.3. Initial operation families

- creation/random/fill;
- indexing/slicing/views;
- reshape/transpose;
- arithmetic;
- reductions;
- matmul/batched matmul;
- softmax/log-softmax;
- normalization primitives;
- activation primitives;
- gather/scatter;
- selected convolution/attention primitives later.

## 142.4. Backend policy

Reference CPU backend exists for correctness. Accelerated backends may initially delegate to external engines/native libraries.

---

# 143. Autograd specification

## 143.1. Required concepts

- `requiresGradient`;
- graph/tape;
- `backward()`;
- leaf/non-leaf tensors;
- gradient accumulation;
- detach;
- no-grad scope;
- custom backward rule;
- gradient hooks.

## 143.2. Correctness first

Performance optimization is secondary until differential correctness suite passes against trusted reference implementations for supported operations.

## 143.3. Memory

Autograd must expose policies for graph retention, checkpointing/recomputation and release of intermediate tensors.

---

# 144. Neural module and architecture authoring

## 144.1. Module contract

```java
public interface Module<I, O> {
    O forward(I input);
}
```

Specialized tensor modules may expose parameters/buffers/submodules.

## 144.2. Requirements

- parameter registration;
- nested modules;
- train/eval mode;
- state dict equivalent;
- initialization policies;
- serialization;
- custom operation extension;
- inspectable graph/module tree;
- notebook-friendly representation.

## 144.3. Research target

A researcher must be able to implement a new attention/block architecture entirely in JVM source without modifying CortexJ core.

---

# 145. Distributed training specification

## 145.1. Phased scope

Phase A:
- single-node multi-GPU data parallel.

Phase B:
- multi-node data parallel;
- collective abstraction;
- fault/retry semantics.

Phase C:
- tensor parallel;
- pipeline parallel;
- sharded optimizer/checkpoints.

## 145.2. Planner reuse

Distributed topology planning SHOULD reuse capability/device abstractions from Runtime/Train but live in a separate planner to avoid coupling inference decisions with training topology.

---

# 146. Academic and research workflows

CortexJ Research should eventually support:

- reproducible paper experiment manifests;
- benchmark suites;
- standard dataset loaders;
- model cards;
- dataset cards;
- citation metadata;
- seed/environment capture;
- artifact checksums;
- exportable experiment bundle;
- reference implementations used for education and validation.

Academic usability is measured by reproducibility and inspectability, not only raw training throughput.

---

# 147. Cross-lifecycle model artifact flow

Target flow:

```text
External/PyTorch model
        │
        ▼
Model Hub / Import
        │
        ├──────────────► CortexJ Runtime
        │
        ▼
CortexJ Lab / Data
        │
        ▼
CortexJ Train / PEFT
        │
        ▼
Checkpoint / Adapter / Model Artifact
        │
        ├──────────────► Experiment Store
        │
        └──────────────► CortexJ Runtime
```

The same immutable model/dataset/artifact identifiers should propagate through this lifecycle.

---

# 148. Cross-track provenance contract

Every managed artifact SHOULD be able to answer:

```text
Who/what produced me?
From which model revision?
From which dataset fingerprint?
With which code/platform version?
With which parameters/seeds?
On which backend/hardware?
Which parent artifacts exist?
Which checksum proves integrity?
```

Provenance schema must be versioned independently of any one storage implementation.

---

# 149. Security separation: Runtime vs Research

## Runtime default trust

- no arbitrary downloaded code execution;
- signed/checksummed artifacts where configured;
- repository trust policy;
- native library policy;
- network egress policy.

## Research default trust

Research necessarily executes user-authored model/training code. Therefore it runs under a different trust profile and MUST NOT weaken Runtime repository safety defaults.

Notebook kernels and training workers should support process/container isolation profiles for untrusted experiments.

---

# 150. API evolution strategy across tracks

- Runtime stable API follows strict semantic versioning and compatibility policy.
- Lab/Research APIs may incubate under `experimental` packages before stabilization.
- Tensor/Autograd public contracts require dedicated ADR and compatibility tests before 1.0.
- Experimental APIs must not leak into stable Runtime signatures.
- BOM can align versions while allowing tracks to have different maturity labels.

---

# 151. New ADRs required by v3.0

In addition to existing Runtime ADRs:

16. ADR-016 Product-track dependency boundaries.
17. ADR-017 Java baselines per track.
18. ADR-018 Notebook/kernel integration strategy.
19. ADR-019 DataFrame/Dataset abstraction vs adapters.
20. ADR-020 Provenance schema.
21. ADR-021 Training backend SPI.
22. ADR-022 PEFT artifact model.
23. ADR-023 PyTorch/LibTorch interoperability scope.
24. ADR-024 Tensor memory ownership model.
25. ADR-025 Tensor backend SPI.
26. ADR-026 Autograd graph model.
27. ADR-027 Distributed collective abstraction.
28. ADR-028 Research-code isolation model.

---

# 152. Technical spikes after Runtime stabilization

## Spike F — Notebook
Run CortexJ Runtime from a Java Jupyter kernel, render streamed tokens, table and ExecutionPlan.

## Spike G — Data
Read Parquet/Arrow, batch deterministically, fingerprint dataset, pass to a model evaluation pipeline.

## Spike H — Experiment provenance
Run the same experiment twice and prove identical captured model/dataset/runtime metadata.

## Spike I — LoRA
Fine-tune one small model through an external training engine from Java API and deploy resulting adapter through CortexJ Runtime.

## Spike J — PyTorch interop
Import/export one representative checkpoint/adapter flow without Python in the deployment phase.

## Spike K — Tensor
Implement reference CPU tensor matmul/reduction and one accelerated provider behind the same API.

## Spike L — Autograd
Validate gradients for a small MLP against a trusted reference within specified numerical tolerance.

---

# 153. Release train proposal

Suggested high-level releases:

```text
CortexJ Runtime 0.x   → P0 incubation
CortexJ Runtime 1.0   → stable production runtime
CortexJ Lab 0.x       → P1/P2/P3 incubation
CortexJ Train 0.x     → P4/P5/P6 incubation
CortexJ Tensor 0.x    → P7/P8 incubation
CortexJ Research 0.x  → P9/P10/P11 incubation
```

Platform marketing version may exist separately, but individual tracks publish their maturity explicitly.

---

# 154. Definition of Done — CortexJ Lab 1.0

1. Supported Java baseline documented.
2. Jupyter-compatible workflow works on supported OS matrix.
3. Runtime model load/generation works interactively.
4. Frame/table rich display works.
5. charts/images/metrics rendering works.
6. notebook environment is reproducibly describable.
7. experiment provenance can be captured.
8. notebook → Maven project/example export path documented.
9. cancellation/resource cleanup works.
10. Python kernel is not required for core supported workflows.

---

# 155. Definition of Done — CortexJ Train 1.0

1. At least one PEFT path is production-usable.
2. Checkpoint/resume works.
3. Dataset/model/hyperparameter provenance complete.
4. Training metrics stream to Experiments/Lab.
5. Memory/device planning exists.
6. At least two backend strategies are supported or one backend plus remote trainer provider.
7. Output adapter/model artifact is deployable through Runtime.
8. Unsupported backend/model combinations fail before expensive execution.
9. Mixed precision behavior is explicit.
10. Training run is reproducible within documented numerical constraints.

---

# 156. Definition of Done — CortexJ Tensor/Autograd 1.0

1. Stable Tensor/Shape/DType/Device API.
2. Reference CPU backend.
3. At least one accelerated backend/provider.
4. Core op conformance suite.
5. deterministic shape/broadcast semantics.
6. explicit memory/resource lifecycle.
7. autograd gradient correctness suite.
8. custom Module training demonstration.
9. serialization/state support.
10. no Runtime dependency on Tensor/Autograd artifacts unless explicitly selected.

---

# 157. End-to-end north-star demonstration

Long-term reference demo:

```text
Java notebook
    ↓
Load Parquet dataset
    ↓
Explore / visualize
    ↓
Load pretrained model from Hub
    ↓
Create reproducible Experiment
    ↓
Fine-tune with LoRA
    ↓
Evaluate
    ↓
Export adapter/model artifact
    ↓
Lock revision + provenance
    ↓
Deploy with CortexJ Runtime
    ↓
Execution Planner chooses best backend
    ↓
Production Java service serves inference
```

For P9+ an additional demo must replace the pretrained architecture with a custom JVM-authored Module.

---

# 158. Strategic sequencing rule

A later phase MUST NOT block release of an earlier useful phase.

Examples:

- Runtime releases do not wait for Tensor.
- Lab can integrate existing Data libraries before own abstraction reaches full maturity.
- Train can use external engines before CortexJ Tensor is production-ready.
- Research can mix CortexJ API with interoperable external artifacts.

This rule prevents the project from becoming a multi-year framework that delivers no useful product until every layer exists.

---

# 159. Scope-control gates

Before starting each priority after P0, maintainers must publish:

1. concrete user problem;
2. why existing JVM library integration is insufficient alone;
3. minimal adapter-first solution;
4. benchmark/acceptance criteria;
5. dependency impact on Runtime;
6. estimated native/support matrix cost;
7. exit criteria.

If an adapter solves ≥80% of the target use case without breaking abstraction quality, adapter-first implementation is preferred over a new engine.

---

# 160. v3.0 north-star principle

> **CortexJ should let a JVM team stay on the JVM for as much of the AI lifecycle as is technically and economically sensible — while remaining fully interoperable with the Python/PyTorch world when that world is still the best tool for the job.**

The success condition is not eliminating Python. The success condition is eliminating unnecessary language switching and infrastructure duplication.
