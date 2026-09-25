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

Starters for **every supported LTS line** (§7.1 of the spec): `cortexj-starter-java8`,
`cortexj-starter-java11`, `cortexj-starter-java17`, `cortexj-starter-java21`,
`cortexj-starter-java25`. The starter matches your JVM at bootstrap: using a starter
built for a newer JVM fails fast with an actionable diagnostic (AC-JAVA-004), and the
business code stays identical across all five (AC-JAVA-003).

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

# reproducible deployments (spec 63/91)
java -jar cli/target/cortexj-cli-*.jar plan Qwen/Qwen3-8B --lockfile-out release.lock
java -jar cli/target/cortexj-cli-*.jar run Qwen/Qwen3-8B --lockfile release.lock

# air-gap bundles (spec 62)
java -jar cli/target/cortexj-cli-*.jar bundle create Qwen/Qwen3-8B --out ./bundle
java -jar cli/target/cortexj-cli-*.jar bundle verify ./bundle
```

## Architecture

```text
            Direct CortexJ API          Spring AI / LangChain4j / OpenTelemetry adapters
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
| `cortexj-runtime-djl` | DJL provider (honest engine probe, ONNX embeddings when engine artifacts are added) |
| `cortexj-observability` | OpenTelemetry adapter: platform events to OTel counters/spans |
| `integrations/spring-ai` | Spring AI adapter: `ChatModel`, `StreamingChatModel`, `EmbeddingModel` (Java 17+) |
| `integrations/langchain4j` | LangChain4j adapter: `ChatModel`, `StreamingChatModel`, `EmbeddingModel` (Java 17+) |
| `cortexj-platform-java8..25` | LTS platform modules |
| `cortexj-starter-java8/11/17/21/25` | Maven starters with JVM validation |
| `cortexj-tck` | Technology compatibility kit for runtime/repository providers |
| `cortexj-cli` | `doctor / runtimes / hardware / inspect / plan / pull / run / bundle` |

## Status (milestones from the spec)

- **M0** — architecture skeleton, API/SPI, module boundaries: done
- **M1** — model resolution (local + Hugging Face), cache, immutable revisions: done
- **M4** — execution planner: candidates, hard constraints, scoring, memory estimates,
  explainable deterministic plans: done
- **M2** — runtime SPI registry, core platform, OpenAI-compatible remote provider: done
- **M3** — ONNX provider (probe/load/tokenizer/embeddings); llama.cpp probe + actionable
  diagnostics (JNI execution bridge intentionally deferred)
- **M5** — platform modules for Java 8/11/17/21/25 + starters for all five LTS lines with
  wrong-JVM detection
- **M6** — legacy support: verified by building and testing the full shared core on real
  JDK 8 and JDK 11 (M6 acceptance: starter-java8/java11 present, 87 tests green on both)
- **M7** — production integration: `ModelLockfile` (PINNED_MODEL/PINNED_PLAN enforcement,
  `--lockfile-out`/`--lockfile` CLI flags), air-gap bundles (`cortexj bundle create/verify`,
  sha256 manifests, verified offline re-resolution), OpenTelemetry adapter
  (`cortexj-observability`), Spring AI adapter, LangChain4j adapter
- **M8** — extended runtimes: DJL provider (`cortexj-runtime-djl`, honest probe — engine
  artifacts stay optional per the spec); JInfer verified **absent from Maven Central**
  (spec DoD §107.5 explicitly requires MVP to work without JInfer — no adapter is possible
  against a non-published artifact); OpenAI-compatible remote provider covers vendor endpoints
- **M9** — conversion subsystem (spec §31 Level C, §32): `ConversionStep` in candidates,
  planner proposes deterministic conversions when no direct artifact is viable, core
  materializes conversions into the cache with a provenance manifest (§32.4), original
  artifacts stay immutable

Current test matrix: **26 suites / 127 tests green on JDK 25** (full reactor incl. adapter
modules), **23 suites / 116 tests green on real JDK 8** (shared core + providers + TCK).

Known follow-ups (explicit non-goals of this drop, tracked by the spec's v1.0 scope):
llama.cpp JNI bridge, Safetensors direct execution, real format converters (the conversion
machinery is in place — SPI, planning, materialization, provenance — but no production
converter ships yet), JInfer (not on Central), server mode.

## Build

The reactor adapts to the building JDK via profiles:

| Build JDK | Modules built |
|---|---|
| 8 | shared core, platform-java8, starter-java8, providers, TCK |
| 11 | + platform-java11, starter-java11 |
| 17 | + platform-java17, starter-java17 |
| 21 | + platform-java21, starter-java21 |
| 25 | + platform-java25, starter-java25, CLI (fat jar) |

```text
mvn clean install
```

Multi-JDK verification (executed on real JDKs 8/11/17/21/25):

- `mvn test` green on each LTS JDK — 87 tests, 0 failures on every lane
  (JDK 8 build uses `-source/-target 8`; JDK 9+ uses `--release 8`).
- The single shaded CLI jar runs unchanged on Java 8 through 25, reporting the correct
  JVM each time; newer platform classes are skipped gracefully on older JVMs, and the
  matching `PlatformModule` is selected at runtime (fixed pool on 8–17, virtual threads
  from 21+).
- `cortexj-starter-java25` on a Java 8 JVM fails fast with `CORTEXJ-CONF-0002`
  and the exact remediation: *replace with cortexj-starter-java8*.

87 automated tests across 16 suites: planner synthetic matrices (determinism, hard
constraints, JVM/quality/offline policies), provider TCK, download integrity, cache safety,
and an end-to-end load→plan→execute→close cycle.

## Configuration priority

programmatic builder > system properties (`cortexj.*`) > environment variables
(`CORTEXJ_*`). Offline mode: `cortexj.offline=true` / `CORTEXJ_OFFLINE=1`. Remote runtime:
`CORTEXJ_REMOTE_BASE_URL` (+ `CORTEXJ_REMOTE_API_KEY`). Hugging Face token:
`CORTEXJ_HF_TOKEN` (never logged).
