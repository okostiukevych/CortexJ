# CortexJ

**CortexJ — AI runtime orchestration and model portability platform for the JVM.**

One JVM API. Any compatible model. The best available runtime. From legacy Java 8 applications
to modern Java 25 systems, across interchangeable local and remote runtimes.

```java
CortexJ platform = CortexJ.builder().build();

try (ModelHandle model = platform.models().load(ModelReference.of("Qwen/Qwen3-8B"))) {
    GenerationResponse response = model
            .capability(TextGenerationCapability.class)
            .generate(GenerationRequest.prompt("Explain CQRS"));
    System.out.println(response.text());
}
```

The platform answers the infrastructure questions itself: where the model lives, which
revision to use, which artifacts are compatible, which runtimes are installed, what hardware
is available, whether memory suffices, what the fallback is — and *why* the chosen
execution path was selected.

## Quick start

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.cortexj</groupId>
            <artifactId>cortexj-bom</artifactId>
            <version>0.1.0-SNAPSHOT</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependency>
    <groupId>io.cortexj</groupId>
    <artifactId>cortexj-starter-java21</artifactId>
</dependency>
```

Starters available: `cortexj-starter-java17`, `cortexj-starter-java21`, `cortexj-starter-java25`
(Java 8/11 platform modules already ship as part of the shared Java 8 baseline).

## CLI

```text
java -jar cli/target/cortexj-cli-*.jar doctor
java -jar cli/target/cortexj-cli-*.jar runtimes
java -jar cli/target/cortexj-cli-*.jar hardware
java -jar cli/target/cortexj-cli-*.jar inspect Qwen/Qwen3-8B
java -jar cli/target/cortexj-cli-*.jar plan Qwen/Qwen3-8B --offline
java -jar cli/target/cortexj-cli-*.jar plan Qwen/Qwen3-8B --runtime llamacpp
java -jar cli/target/cortexj-cli-*.jar pull Qwen/Qwen3-8B
java -jar cli/target/cortexj-cli-*.jar run Qwen/Qwen3-8B --prompt "Explain MVCC"
```

## Architecture

```text
            Direct CortexJ API          Spring AI / LangChain4j (adapters, planned)
                    │
                 CORTEXJ
   Model Resolver │ Capability/Hardware │ Diagnostics
                    │
             EXECUTION PLANNER
  Candidates │ Policies │ Memory Model │ Explanation
                    │
              Runtime SPI (ServiceLoader)
    ONNX │ llama.cpp │ OpenAI-remote │ future X
                    │
              CPU / GPU / NPU
```

Layering rules (enforced by module boundaries):

- `cortexj-api` — shared public contract + SPI packages (`io.cortexj.spi.*`), Java 8 bytecode baseline.
  No framework and no engine dependencies (AP-001, AP-002).
- `cortexj-core` — bootstrap, registries, download manager, lifecycle, fallback. Never depends on a
  concrete runtime (AC-RUNTIME-001).
- Runtimes are plugins discovered via `ServiceLoader`; planner decisions are driven by descriptors
  and policies, never by provider order (deterministic scoring + tie-breaks).
- LTS-specific behavior lives in `cortexj-platform-java{8,11,17,21,25}` behind the
  `PlatformModule` SPI (virtual threads from Java 21+).

## Modules

| Module | Purpose |
|---|---|
| `cortexj-api` | Public API, planner types, streaming, diagnostics, errors, SPI contracts |
| `cortexj-spi` | Provider developer kit (contexts, support classes) |
| `cortexj-model` | Artifact inspection: real GGUF binary parser, safetensors header, ONNX probe |
| `cortexj-hardware` | Environment/hardware discovery (safe probes, no shell-outs) |
| `cortexj-cache` | Checksum-safe cache, atomic writes, LRU eviction, path-traversal hardening |
| `cortexj-planner` | Execution planner: hard constraints, scoring policies, memory estimation, explanations |
| `cortexj-core` | Platform bootstrap, ServiceLoader registries, download manager, lifecycle, fallback |
| `cortexj-streaming` | `TokenStream` implementation utilities |
| `cortexj-repository-local` | Local filesystem repository (`cortexj-model.json` manifests) |
| `cortexj-repository-huggingface` | Hugging Face resolver (immutable revisions, offline mode) |
| `cortexj-runtime-remote-openai` | OpenAI-compatible remote provider (vLLM, llama-server, gateways) |
| `cortexj-runtime-onnx` | ONNX Runtime provider (probe, session load, Hf tokenizer, embeddings) |
| `cortexj-runtime-llamacpp` | llama.cpp provider (probe + honest diagnostics; JNI bridge is a later milestone) |
| `cortexj-platform-java8..25` | LTS platform modules |
| `cortexj-starter-java17/21/25` | Maven starters with JVM validation |
| `cortexj-tck` | Technology compatibility kit for runtime/repository providers |
| `cortexj-cli` | `doctor / runtimes / hardware / inspect / plan / pull / run` |

## Status (milestones from the spec)

- **M0** — architecture skeleton, API/SPI, module boundaries: done
- **M1** — model resolution (local + Hugging Face), cache, immutable revisions: done
- **M4** — execution planner: candidates, hard constraints, scoring, memory estimates,
  explainable deterministic plans: done
- **M2** — runtime SPI registry, core platform, OpenAI-compatible remote provider: done
- **M3** — ONNX provider (probe/load/tokenizer/embeddings); llama.cpp probe + actionable
  diagnostics (JNI execution bridge intentionally deferred)
- **M5** — platform modules for Java 8/11/17/21/25 + starters 17/21/25 with wrong-JVM detection

Known follow-ups (explicit non-goals of this drop, tracked by the spec's v1.0 scope):
llama.cpp JNI bridge, Safetensors direct execution, conversion subsystem, lockfile/air-gap
bundle, Spring AI/LangChain4j adapters, JInfer/DJL providers, server mode.

## Build

```text
JDK 21+ required to build (LTS platform modules up to release 25); artifacts themselves
target Java 8 where applicable.

mvn install
```

87 automated tests across 13 test suites: planner synthetic matrices (determinism, hard
constraints, JVM/quality/offline policies), provider TCK, download integrity, cache safety,
and an end-to-end load→plan→execute→close cycle.

## Configuration priority

programmatic builder > system properties (`cortexj.*`) > environment variables
(`CORTEXJ_*`). Offline mode: `cortexj.offline=true` / `CORTEXJ_OFFLINE=1`. Remote runtime:
`CORTEXJ_REMOTE_BASE_URL` (+ `CORTEXJ_REMOTE_API_KEY`). Hugging Face token:
`CORTEXJ_HF_TOKEN` (never logged).
