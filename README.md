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

# cache management (spec 59, 33.4)
java -jar cli/target/cortexj-cli-*.jar cache list
java -jar cli/target/cortexj-cli-*.jar cache clean
```

Guides for every supported runtime, adapter and extension point live under `docs/`
(spec 101/102).

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
| `cortexj-repository-http` | Plain HTTP(S) URL references (ETag revisions, trust notes) |
| `cortexj-runtime-remote-openai` | OpenAI-compatible remote provider (vLLM, llama-server, gateways) |
| `cortexj-runtime-onnx` | ONNX Runtime provider (probe, session load, Hf tokenizer, embeddings) |
| `cortexj-runtime-llamacpp` | llama.cpp provider: probe, **managed llama-server process bridge** (blocking + SSE execution), honest diagnostics |
| `cortexj-runtime-djl` | DJL provider (honest engine probe, ONNX embeddings when engine artifacts are added) |
| `cortexj-runtime-isolated` | Isolated native worker mode (spec 127): child-JVM runtime host for crash isolation, opt-in per runtime |
| `cortexj-worker` | Worker host main: NDJSON stdio protocol bridging a RuntimeProvider inside a child JVM |
| `cortexj-observability` | OpenTelemetry adapter: platform events to OTel counters/histograms/spans |
| `cortexj-streaming-flow` | Java `Flow.Publisher` streaming adapter (Java 11+) |
| `cortexj-convert-gguf` | Deterministic GGUF F32↔F16 converter (Level C, provenance manifests) |
| `cortexj-convert-safetensors` | Deterministic Safetensors→GGUF re-containerization (config.json metadata, Level C) |
| `cortexj-declarative` | Declarative interface API: `@ModelPrompt` + dynamic proxy, `JsonPromptDecoder` (spec 49) |
| `cortexj-server` | Optional OpenAI-compatible embedded server (spec 60) |
| `cortexj-maven-plugin` | `cortexj:verify` build-time deployment verification (spec 93/94) |
| `cortexj-examples` | Compilable spec examples: Java 8 legacy app, plan-before-execution, explicit ONNX, third-party runtime plugin (spec 78-84) |
| `cortexj-examples-spring-ai` | Spring AI bean wiring example (spec 83, Java 17+) |
| `integrations/spring-ai` | Spring AI adapter: `ChatModel`, `StreamingChatModel`, `EmbeddingModel` (Java 17+) |
| `integrations/langchain4j` | LangChain4j adapter: `ChatModel`, `StreamingChatModel`, `EmbeddingModel` (Java 17+) |
| `cortexj-platform-java8..25` | LTS platform modules |
| `cortexj-starter-java8/11/17/21/25` | Maven starters with JVM validation |
| `cortexj-tck` | Technology compatibility kit for runtime/repository providers |
| `cortexj-cli` | `doctor / runtimes / hardware / inspect / plan / pull / run / server / convert / cache list,clean / bundle create,verify` |

## Status (milestones from the spec)

- **M0** — architecture skeleton, API/SPI, module boundaries: done
- **M1** — model resolution (local + Hugging Face), cache, immutable revisions: done
- **M4** — execution planner: candidates, hard constraints, scoring, memory estimates,
  explainable deterministic plans: done
- **M2** — runtime SPI registry, core platform, OpenAI-compatible remote provider: done
- **M3** — ONNX provider (probe/load/tokenizer/embeddings); llama.cpp provider with
  probe + actionable diagnostics and **real local execution** via the managed
  llama-server process bridge
- **M5** — platform modules for Java 8/11/17/21/25 + starters for all five LTS lines with
  wrong-JVM detection
- **M6** — legacy support: verified by building and testing the full shared core on real
  JDK 8 and JDK 11 (M6 acceptance: starter-java8/java11 present, the full shared
  core suite green on both)
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
  artifacts stay immutable; production converters ship: `cortexj-convert-gguf`
  (deterministic F32↔F16, `cortexj convert` CLI) and `cortexj-convert-safetensors`
  (Safetensors→GGUF re-containerization with config.json metadata — verified end-to-end
  through Level C planning); multimodal/task planning (§47): a runtime must support at
  least one task the model offers; spec 78-84 examples are compilable
  (`cortexj-examples`); declarative API ships (`cortexj-declarative`)
- **M10** — future track pulled forward: **enterprise runtime policy** (§128,
  `enterprise-policy.properties` auto-applied: forbid runtimes/downloads/licenses/
  unverified artifacts/GPU/remote execution), **benchmark-aware planner hints** (§126,
  `benchmarks.properties` — deterministic bounded scoring, no learning), **isolated
  native worker** (§127, `cortexj-runtime-isolated` + `cortexj-worker`: child-JVM
  runtime host with crash isolation, public API unchanged)

Full spec-conformance passes additionally delivered: cache eviction policies
(pinned/age-cap/manual + `cache list/clean` CLI), trust policies (TRUSTED_ONLY /
ALLOW_UNSIGNED_WITH_WARNING / CUSTOM, spec 35.2), a plain HTTP repository provider
(spec 14.3), the Java Flow streaming adapter (spec 37.3), duration/token metrics
(PLAN_CREATED / DOWNLOAD_COMPLETED / INFERENCE_COMPLETED + first-token latency and
queue wait, surfaced through the OTel histogram, spec 57.1), download resume
(HTTP Range), cancellation and proxy support (spec 34), the documentation set required
by spec 101/102 under `docs/`, API stability tiers + japicmp `compat` profile
(spec 73/74), the formal security review record (§108.12, `docs/security-review.md`),
GPU self-hosted CI lanes (§70) and GraalVM compatibility notes (§96).

Current test matrix: **~126 test classes / ~457 tests green on JDK 25** (13 gated
skips are loud assumeTrue, never silent); the full 2026-10-01 audit pass (31 issues
resolved: concurrency refcount races, carrier pinning, executor bounds, download
caps/retry/resume validation, parser hardening, server hardening, pinned in-use
artifacts) is regression-tested. GitHub Actions CI matrix runs
JDK 8/11/17/21/25 on ubuntu/macos/windows, JPMS lanes (`mvn -Pjpms`),
SpotBugs + CodeQL + JaCoCo coverage lanes (issue #53), and gated self-hosted
GPU lanes (CUDA/Metal per spec 70).

llama.cpp execution is real: the provider manages a `llama-server` process (health
polling, blocking + SSE streaming, graceful shutdown incl. destroyForcibly escalation
and orphan-free teardown on Ctrl+C/close/failure) when
`CORTEXJ_LLAMACPP_SERVER` points at the binary (verified 2026-09-30: issues #14, #15, #29). §95 JPMS is implemented as the
`jpms` build profile (multi-release module-info jars, verified with
`jar --describe-module`); §60 server mode ships as `cortexj-server` +
`cortexj server` CLI; §93/94 ship as `cortexj-maven-plugin` (`cortexj:verify`).

Everything required by the spec's v1.0 scope (§105) and DoD (§107/108) is implemented,
and the three locally-tractable future items are pulled forward (M10): enterprise
runtime policy (§128), benchmark-aware planner hints (§126), isolated native worker
mode (§127). Remaining items are only what the spec itself marks as impossible,
optional, or future: JInfer (artifact absent from Maven Central — §107.5 requires
MVP to work without it), GPU CI lanes (require self-hosted CUDA/Metal runners —
prepared and gated), server multimodal endpoints (§60 "future"), and the
`cortexj-declarative` evolution beyond prompt+validation (§48/§49).
Before a public 1.0 release: Maven Central publishing,
the API stability freeze process (§73/74 — tiers documented, `compat` profile ready),
and a signed-off production security review cycle on top of the recorded one.

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

- `mvn test` green on each LTS JDK — the shared core suite (~415 tests on the
  JDK 8 lane, ~457 on JDK 25), 0 failures on every lane
  (JDK 8 build uses `-source/-target 8`; JDK 9+ uses `--release 8`).
- The single shaded CLI jar runs unchanged on Java 8 through 25, reporting the correct
  JVM each time; newer platform classes are skipped gracefully on older JVMs, and the
  matching `PlatformModule` is selected at runtime (fixed pool on 8–17, virtual threads
  from 21+).
- `cortexj-starter-java25` on a Java 8 JVM fails fast with `CORTEXJ-CONF-0002`
  and the exact remediation: *replace with cortexj-starter-java8*.

~457 automated tests across ~126 test classes: planner synthetic matrices (determinism, hard
constraints, JVM/quality/offline policies, benchmark hints, enterprise policy rules), provider
TCK (lifecycle, streaming, cancellation, registration contracts), concurrency/race regression
suite (double-close, cold-load races, close-during-stream, cancellation - tagged `concurrency`),
download integrity + retry matrix, cache safety + amortized eviction, resource-leak assertions
(process exit, executor termination, temp cleanup, handle closure), isolated-worker child-JVM
round-trips (crash, streaming, leaks), and end-to-end load→plan→execute→close cycles.

## Configuration priority

programmatic builder > system properties (`cortexj.*`) > environment variables
(`CORTEXJ_*`). Offline mode: `cortexj.offline=true` / `CORTEXJ_OFFLINE=1`. Remote runtime:
`CORTEXJ_REMOTE_BASE_URL` (+ `CORTEXJ_REMOTE_API_KEY`). Hugging Face token:
`CORTEXJ_HF_TOKEN` (never logged).
