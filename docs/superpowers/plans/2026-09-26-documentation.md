# CortexJ Documentation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or execute inline task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Public rendered documentation at `https://okostiukevych.github.io/CortexJ-site/docs/` — MkDocs Material, Diátaxis structure, 17 pages, built by the existing Pages workflow.

**Architecture:** Docs markdown lives in `site-docs/docs/` of the public `CortexJ-site` repo (local path `/Users/oleg/NikitaProjects/CortexJ-site`). `mkdocs.yml` at repo root (`docs_dir: site-docs`, `site_dir: _site`) builds to `_site/docs/…`; the workflow overlays the landing files at `_site/` root and deploys. Landing gains a Docs nav link. `--strict` build fails on broken links.

**Tech Stack:** Markdown, MkDocs + Material for MkDocs, GitHub Actions Pages deploy.

**Context for the executor:**
- Verified fact sources (private repo): `README.md`, `docs/*.md` (16 files), `docs/api-stability.md`. **Accuracy rule: only claims/code from these sources may appear. Never invent API names, flags, error codes, or examples. If a page has no verified example for something, describe it without code.**
- Verified error codes: `CORTEXJ-CONF-0002` (wrong-JVM starter), `CORTEXJ-MODEL-1003` (offline + uncached), `CORTEXJ-PLAN-4002` (lockfile mismatch), `CORTEXJ-SEC-10001` (trust policy refusal), `CORTEXJ-ARTIFACT-2001` (checksum mismatch), `CORTEXJ-ARTIFACT-2005` (path traversal), families `CORTEXJ-LOAD-*` / `CORTEXJ-INFER-*` / `CORTEXJ-NATIVE-*`.
- Verified reason codes: `FORMAT_UNSUPPORTED`, `TASK_UNSUPPORTED`, `PINNED_PLAN`, `PINNED_MODEL`, `USER_REQUIREMENT_CONFLICT`, `MODEL_OFFLINE_UNRESOLVED`, `NATIVE_LIBRARY_MISSING`.

**Voice & style (all pages):**
- Second person, active voice, present tense, no marketing adjectives.
- Each page opens with 1–2 sentences answering "what is this".
- Code first, explanation after; fenced blocks with language tags.
- Material admonitions (`!!! note`, `!!! warning`) for gotchas.
- Cross-page links are relative Markdown (e.g. `[planner](planner.md)` inside `concepts/`, `[Installation](../installation.md)` from subfolders).
- Status line where relevant: the library is `0.1.0-SNAPSHOT`, pre-Maven Central.

**Commits:** single site-repo commit at the end (per user's established preference), then private-repo spec+plan commit.

---

### Task 1: `mkdocs.yml` + brand CSS

**Files:**
- Create: `/Users/oleg/NikitaProjects/CortexJ-site/mkdocs.yml`
- Create: `/Users/oleg/NikitaProjects/CortexJ-site/site-docs/assets/brand.css`

- [ ] **Step 1: Write `mkdocs.yml`**

```yaml
site_name: CortexJ
site_url: https://okostiukevych.github.io/CortexJ-site/
site_description: AI runtime orchestration and model portability for the JVM — one API, any model, the best available runtime.
repo_url: https://github.com/okostiukevych/CortexJ-site
repo_name: okostiukevych/CortexJ-site
docs_dir: site-docs
site_dir: _site
nav:
  - Home: docs/index.md
  - Installation: docs/installation.md
  - Quickstart: docs/quickstart.md
  - Concepts:
      - Models & repositories: docs/concepts/models.md
      - The execution planner: docs/concepts/planner.md
      - Runtimes: docs/concepts/runtimes.md
      - Hardware & memory: docs/concepts/hardware.md
      - Capabilities: docs/concepts/capabilities.md
      - Configuration: docs/concepts/configuration.md
  - CLI reference: docs/cli.md
  - Integrations: docs/integrations.md
  - Production: docs/production.md
  - Declarative API: docs/declarative.md
  - Converters: docs/converters.md
  - Provider SPI: docs/providers.md
  - Reference:
      - Configuration properties: docs/reference/configuration-properties.md
      - API stability: docs/reference/api-stability.md
theme:
  name: material
  language: en
  font:
    text: Public Sans
    code: JetBrains Mono
  palette:
    - scheme: default
      toggle:
        icon: material/brightness-7
        name: Switch to dark mode
    - scheme: slate
      toggle:
        icon: material/brightness-4
        name: Switch to light mode
  features:
    - navigation.sections
    - navigation.top
    - navigation.footer
    - search.highlight
    - search.suggest
    - content.code.copy
    - toc.follow
extra_css:
  - assets/brand.css
markdown_extensions:
  - admonition
  - pymdownx.details
  - pymdownx.superfences
  - pymdownx.highlight:
      anchor_linenums: true
  - pymdownx.inlinehilite
  - tables
  - toc:
      permalink: true
```

- [ ] **Step 2: Write `site-docs/assets/brand.css`**

```css
:root {
  --md-primary-fg-color: #7c6ff0;
  --md-primary-fg-color--light: #9c92f5;
  --md-primary-fg-color--dark: #5d4fd4;
  --md-accent-fg-color: #5d4fd4;
}
[data-md-color-scheme="slate"] {
  --md-primary-fg-color: #7c6ff0;
  --md-primary-fg-color--light: #9c92f5;
  --md-primary-fg-color--dark: #a5a1f2;
  --md-accent-fg-color: #a5a1f2;
}
```

---

### Task 2: `pages.yml` — build docs + overlay landing

**Files:**
- Modify: `/Users/oleg/NikitaProjects/CortexJ-site/.github/workflows/pages.yml` (replace whole file)

- [ ] **Step 1: Write the new workflow**

```yaml
name: Pages

on:
  push:
    branches: [main]
  workflow_dispatch:

permissions:
  contents: read
  pages: write
  id-token: write

concurrency:
  group: pages
  cancel-in-progress: true

jobs:
  deploy:
    name: Deploy to GitHub Pages
    runs-on: ubuntu-latest
    environment:
      name: github-pages
      url: ${{ steps.deployment.outputs.page_url }}
    steps:
      - name: Checkout
        uses: actions/checkout@v4
      - name: Set up Python
        uses: actions/setup-python@v5
        with:
          python-version: '3.12'
      - name: Install MkDocs Material
        run: pip install mkdocs-material
      - name: Build docs
        run: mkdocs build --strict
      - name: Overlay landing
        run: cp index.html styles.css favicon.svg _site/
      - name: Upload site artifact
        uses: actions/upload-pages-artifact@v3
        with:
          path: _site
      - name: Deploy
        id: deployment
        uses: actions/deploy-pages@v4
```

- [ ] **Step 2: Validate YAML locally**

```bash
python3 -c "import yaml; yaml.safe_load(open('/Users/oleg/NikitaProjects/CortexJ-site/.github/workflows/pages.yml')); print('yaml ok')"
```

Expected: `yaml ok`.

---

### Task 3: Landing — Docs link

**Files:**
- Modify: `/Users/oleg/NikitaProjects/CortexJ-site/index.html` (two lines)

- [ ] **Step 1: Add Docs link to nav**

In `.nav-links`, after `<a href="#who">Who it's for</a>`, add:

```html
      <a href="/CortexJ-site/docs/">Docs</a>
```

- [ ] **Step 2: Add docs URL to footer meta**

Change the footer-meta paragraph to:

```html
    <p class="footer-meta">© 2026 CortexJ · Apache License 2.0 · <a href="/CortexJ-site/docs/">Documentation</a> · okostiukevych.github.io/CortexJ-site</p>
```

---

### Tasks 4–20: documentation pages

Each page below lists: target file, H2 outline, and the **verified fact pack** (code blocks must be copied verbatim from the pack). Write prose per the Voice & style rules; do not add facts beyond the pack.

**Task 4 — `site-docs/docs/index.md` (docs home)**
Outline: What is CortexJ · Why CortexJ (feature grid) · Where to go next.
Fact pack: intro paragraphs adapted from the landing/README ("One JVM API. Any model. The best available runtime."; platform answers infrastructure questions itself — where the model lives, which revision, which runtime, whether memory suffices, what the fallback is, and why). Feature grid: model portability (one API, local/HF/HTTP repositories, immutable revisions); execution planner (deterministic scoring, hard constraints, fallback chains, explanations); Java 8→25 (starters per LTS, identical business code, fail-fast JVM validation); runtimes as plugins (ONNX, llama.cpp, OpenAI-compatible remote, DJL); integrations (Spring AI, LangChain4j, OpenTelemetry); production (lockfiles, air-gap bundles, offline, observability). Next-step links: Installation, Quickstart, Concepts. Note: `0.1.0-SNAPSHOT`, pre-release.

**Task 5 — `site-docs/docs/installation.md`**
Outline: Requirements · Add the BOM · Pick your starter · What the starter validates · CLI jar.
Fact pack (verbatim from README/docs):

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
```

Starter table: `cortexj-starter-java8/11/17/21/25` — "Starters for every supported LTS line; the starter matches your JVM at bootstrap; a starter built for a newer JVM fails fast with `CORTEXJ-CONF-0002` and the exact remediation (e.g. *replace with cortexj-starter-java8*); business code stays identical across all five." Requirements: JDK 8–25 any distribution; for local GGUF execution you need llama.cpp; for ONNX embeddings the bundled ONNX Runtime (CPU) is enough. Java 8 note: shared modules compile to Java 8 bytecode; full platform boots on real JDK 8; Java 9+ features (virtual threads, `Flow` adapters, Spring AI/LangChain4j adapters) live behind JDK profiles. Java 21 note: platform module uses virtual threads for I/O orchestration behind the same API. Shaded CLI jar runs unchanged on Java 8 through 25.

**Task 6 — `site-docs/docs/quickstart.md`**
Outline: A complete example · What just happened · Same thing via CLI · Next steps.
Fact pack (code verbatim from docs/getting-started-java8.md):

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

"What just happened": resolution (local → Hugging Face → HTTP), immutable revision pinning, planner picks runtime from hardware/memory/policies with a full explanation, execution, fallback if needed. CLI variant (verbatim):

```bash
java -jar cortexj-cli-0.1.0-SNAPSHOT.jar doctor
java -jar cortexj-cli-0.1.0-SNAPSHOT.jar run Qwen/Qwen3-8B --prompt "hi"
```

**Task 7 — `site-docs/docs/concepts/models.md`**
Outline: Model references · Resolution and immutable revisions · Repositories · The cache · Offline behavior · Trust notes.
Fact pack: `ModelReference.of("Qwen/Qwen3-8B")` (HF id), `ModelReference.of("./local-model.gguf")`-style local refs and `myrepo:` scheme exist per provider `supports()`. Immutable revisions: branch/tag resolved to a hash the repository serves byte-identically later (HF commits; HTTP ETag); plans, cache keys, lockfiles anchor on `resolved.revision()`. Repositories table: `cortexj-repository-local` (filesystem + `cortexj-model.json` manifests + bundles), `cortexj-repository-huggingface` (commit pinning, offline mode, `CORTEXJ_HF_TOKEN`), `cortexj-repository-http` (plain URL references, ETag revisions, always carries a trust note). Cache: `~/.cortexj/cache`, checksum-verified downloads (mismatch → `CORTEXJ-ARTIFACT-2001`, partial file never exposed), atomic writes, LRU eviction with pinned/age-cap/manual policies (`cache list`, `cache clean`). Offline: `CORTEXJ-MODEL-1003` "not cached and offline mode is active" + remediation. Artifact safety: declared size checked before download; `../` traversal in ids → `CORTEXJ-ARTIFACT-2005`. Bundles are local repositories (link to [Production](../production.md)).

**Task 8 — `site-docs/docs/concepts/planner.md`**
Outline: Plan first, execute second · Hard constraints · Soft scoring · Explanations · Fallback · Quality profiles and policies · Lockfile enforcement.
Fact pack: flow Your code → CortexJ planner → Runtime SPI (ServiceLoader) → CPU/GPU/NPU. Hard constraints cannot be bypassed by scores: Java feature version, memory limit, required runtime, offline rules, task support (`TASK_UNSUPPORTED` — a runtime must support at least one task the model offers). Soft scoring with defined tie-breaks; deterministic — same inputs, same plan; provider order never decides. Explanations: every chosen path and every rejected candidate carries a `PlanReason` with a reason code; rejected candidates surface verbatim in plans. Fallback: planner walks a fallback chain when the first choice can't run — no hidden fallback. Quality profiles: `ExecutionRequest.qualityProfile(QualityProfile.MEMORY_FIRST)` → built-in `QualityProfileRule`; chosen quantization is never hidden. Policies (code verbatim):

```java
public final class PrivacyPolicy implements ExecutionPolicy {

    @Override
    public String policyId() { return "privacy-no-remote"; }

    @Override
    public PolicyDecision evaluate(ExecutionCandidate candidate, PlanningContext context) {
        if (candidate.device() == DeviceType.REMOTE) {
            return PolicyDecision.reject(ReasonCode.USER_REQUIREMENT_CONFLICT,
                    "remote execution is forbidden by the privacy policy");
        }
        return PolicyDecision.neutral();
    }
}
```

`PolicyDecision`: `neutral()`, `reject(reasonCode, message)`, `scoreAdjustment(delta, reasonCode, message)` — adjustments never override hard constraints. Registration: `CortexJ.builder().executionPolicy(new PrivacyPolicy()).build()`. Lockfiles: `--lockfile` enforces `PINNED_PLAN`; mismatch fails `CORTEXJ-PLAN-4002` with full explanation; revision drift detected separately. `cortexj plan` asks what will happen before anything runs.

**Task 9 — `site-docs/docs/concepts/runtimes.md`**
Outline: Runtimes are plugins · ONNX Runtime · llama.cpp · OpenAI-compatible remote · DJL · GraalVM notes.
Fact pack: runtimes are ServiceLoader plugins; planner decides from descriptors and policies. ONNX (table verbatim from docs/onnx.md: id `onnx`, formats ONNX, devices CPU (bundled CPU EP only), tasks EMBEDDING, streaming not supported; picks only when the model offers an ONNX artifact and the task is embedding; GGUF-only rejected with `FORMAT_UNSUPPORTED`): tokenizer `HfTokenizer` — WordPiece (CLS/SEP), byte-level BPE, Unigram, whitespace fallback; requirements: `input_ids` int64 input, mean pooling automatic; honest limits: CPU only, text generation not declared. Usage (verbatim):

```java
try (ModelHandle model = platform.models().load(
        ModelReference.of("sentence-transformers/all-MiniLM-L6-v2"))) {
    EmbeddingCapability embedder = model.capability(EmbeddingCapability.class);
    EmbeddingResponse response = embedder.embed(
            EmbeddingRequest.texts(List.of("hello", "world")));
    float[] vector = response.vectors().get(0);
}
```

llama.cpp: managed `llama-server` process — provider starts it with the resolved artifact, polls the health endpoint, serves blocking generation and SSE token streaming, shuts down with the model handle. Probe finds the binary via `CORTEXJ_LLAMACPP_SERVER` / `cortexj.llamacpp.server` or standard prefixes; `CORTEXJ_LLAMACPP_LIB` for the future JNI bridge. Quantization read from the filename when the header is absent; memory estimation from real GGUF header block counts with an uncertainty margin. Example (verbatim):

```bash
export CORTEXJ_LLAMACPP_SERVER=/opt/homebrew/bin/llama-server
java -jar cortexj-cli-0.1.0-SNAPSHOT.jar run ./Qwen3-8B-Q4_K_M.gguf --prompt "hi"
```

Manual server variant (verbatim):

```bash
llama-server -m Qwen3-8B-Q4_K_M.gguf --port 8080
export CORTEXJ_REMOTE_BASE_URL=http://localhost:8080
java -jar cortexj-cli-0.1.0-SNAPSHOT.jar run Qwen/Qwen3-8B-GGUF --prompt "hi"
```

Remote: `openai-remote` — OpenAI-compatible endpoints (vLLM, llama-server, gateways); requires `CortexJ.builder().allowRemoteRuntime(true)`; `CORTEXJ_REMOTE_BASE_URL` + `CORTEXJ_REMOTE_API_KEY`. DJL: honest engine probe; engine artifacts stay optional; ONNX embeddings when artifacts are added. GraalVM: llama-server bridge is pure Java (process + HTTP, no JNI) — the most native-image-friendly path; ONNX binds natives via JNI and needs ONNX Runtime native-image metadata; probe before claiming support.

**Task 10 — `site-docs/docs/concepts/hardware.md`**
Outline: Discovery · Devices · Memory estimation.
Fact pack: safe probes, no shell-outs (README `cortexj-hardware`); devices CPU / GPU / NPU; memory estimation from GGUF header fields (real block counts and sizes) with an uncertainty margin; repository artifact `sizeBytes` feeds estimation — sources without sizes return `0` and the planner applies conservative defaults; a candidate that does not fit memory is a hard-constraint rejection with the reason visible in the plan.

**Task 11 — `site-docs/docs/concepts/capabilities.md`**
Outline: Capability lookup · Text generation · Embeddings · Streaming · Task support.
Fact pack: `model.capability(TextGenerationCapability.class)`; generation: `GenerationRequest.prompt("…")` → `GenerationResponse.text()`; convenience shortcuts `ModelHandle.generate()/embed()`; embeddings: batch required by spec §45, mean pooling, `EmbeddingRequest.texts(...)`/`response.vectors()`; streaming: own `TokenStream` contract (Java 8 baseline), `Flow.Publisher` adapter in `cortexj-streaming-flow` (Java 11+), SSE streaming from llama-server remote-compatible runtimes; task capability constraint: runtime must support at least one task the model offers (`TASK_UNSUPPORTED`); Spring AI/LangChain4j adapters consume the same capabilities (link).

**Task 12 — `site-docs/docs/concepts/configuration.md`**
Outline: Priority chain · Builder methods · Offline mode · Tokens.
Fact pack: priority (verbatim from README): programmatic builder > system properties (`cortexj.*`) > environment variables (`CORTEXJ_*`). Builder methods: `offline(true)`, `allowRemoteRuntime(true)`, `executionPolicy(...)`, `executor(...)` (CortexJBuilder; every subsystem uses the injected `ExecutorService`, closed with the platform; no uncontrolled threads), `tokenSupplier(...)`, `trustPolicy(...)`. Offline three ways:

```java
CortexJ platform = CortexJ.builder().offline(true).build();
```

```bash
java -Dcortexj.offline=true -jar cortexj-cli-*.jar plan <model>
```

plus `CORTEXJ_OFFLINE=1`. Wrong-JVM starter: `CORTEXJ-CONF-0002` with exact remediation. Tokens: supplied via `TokenSupplier` or `CORTEXJ_REMOTE_API_KEY` / `HF_TOKEN` / `HUGGING_FACE_HUB_TOKEN`; never logged, never in error messages or plan explanations; prompts/responses not logged by default (platform events carry metadata only). Full table on the [reference page](reference/configuration-properties.md).

**Task 13 — `site-docs/docs/cli.md`**
Outline: command table + examples (verbatim from README).

```bash
java -jar cli/target/cortexj-cli-*.jar doctor
java -jar cli/target/cortexj-cli-*.jar runtimes
java -jar cli/target/cortexj-cli-*.jar hardware
java -jar cli/target/cortexj-cli-*.jar inspect Qwen/Qwen3-8B
java -jar cli/target/cortexj-cli-*.jar plan Qwen/Qwen3-8B --offline
java -jar cli/target/cortexj-cli-*.jar plan Qwen/Qwen3-8B --runtime llamacpp
java -jar cli/target/cortexj-cli-*.jar pull Qwen/Qwen3-8B
java -jar cli/target/cortexj-cli-*.jar run Qwen/Qwen3-8B --prompt "Explain MVCC"
java -jar cli/target/cortexj-cli-*.jar plan Qwen/Qwen3-8B --lockfile-out release.lock
java -jar cli/target/cortexj-cli-*.jar run Qwen/Qwen3-8B --lockfile release.lock
java -jar cli/target/cortexj-cli-*.jar bundle create Qwen/Qwen3-8B --out ./bundle
java -jar cli/target/cortexj-cli-*.jar bundle verify ./bundle
java -jar cli/target/cortexj-cli-*.jar cache list
java -jar cli/target/cortexj-cli-*.jar cache clean
java -jar cli/target/cortexj-cli-*.jar convert ./model.gguf --to f16 --out ./converted
```

Plus `cortexj server` (embedded OpenAI-compatible server, `cortexj-server` module). Notes: shaded jar runs on Java 8–25; `doctor` = environment sanity; runtimes/hardware = probes and plans; `inspect` = artifact details; link each topic to its concept page.

**Task 14 — `site-docs/docs/integrations.md`**
Outline: Spring AI · LangChain4j · OpenTelemetry.
Fact pack: Spring AI (`cortexj-spring-ai`, Spring AI 1.0.x, Java 17+): blocking + reactor streaming + embeddings code blocks verbatim from docs/spring-ai.md; platform sharing (pass an existing `CortexJ` platform — not closed by the adapter); runtime selection: planner decides — `openai-remote` when `cortexj.remote.base.url` configured; `allowRemoteRuntime(true)` for vLLM/llama-server/gateways. LangChain4j (`cortexj-langchain4j`, 1.0.x, Java 17+): chat + streaming handler + `embedAll` code verbatim; token usage/finish reasons mapped to `TokenUsage`/`FinishReason`; note: multimodal/system prompts beyond the last user message are not translated — use the direct API. OpenTelemetry (`cortexj-observability`): platform events → OTel counters/histograms/spans; metrics: `PLAN_CREATED`, `DOWNLOAD_COMPLETED`, `INFERENCE_COMPLETED`; first-token latency and queue wait surfaced through the OTel histogram.

**Task 15 — `site-docs/docs/production.md`**
Outline: Offline mode · Air-gap bundles · Lockfiles · Trust policies · Artifact safety · Credentials · Native isolation.
Fact pack: offline/bundle/lockfile CLI blocks verbatim from docs/offline-deployment.md (create/verify/run-from-bundle, `--lockfile-out`/`--lockfile`); bundle contents: `cortexj-model.json` manifest (id, resolved immutable revision, architecture, license, tasks, per-artifact sha256 + size), artifacts re-used from `~/.cortexj/cache`; verify checks: manifest parseable, files exist, size/sha256 match (tampering reported), path-traversal safe, non-zero exit on any issue; a bundle is itself a local repository. Lockfiles: `PINNED_PLAN` enforcement → `CORTEXJ-PLAN-4002` + full explanation; revision drift detected separately. Trust policies (verbatim):

```java
CortexJ platform = CortexJ.builder()
        .trustPolicy(TrustPolicy.TRUSTED_ONLY)
        .build();
```

`TRUSTED_ONLY` fails models with a `trustNote` → `CORTEXJ-SEC-10001` + remediation; `ALLOW_UNSIGNED_WITH_WARNING` (default) loads + WARNING event; `CUSTOM` delegates to your execution policies; HTTP repository always sets a trust note, HF marks community models. Artifact limits: size checked before download; traversal → `CORTEXJ-ARTIFACT-2005`; checksum mismatch → `CORTEXJ-ARTIFACT-2001`. Supply chain: repository code never executed (resolution is data-only); everything addressed by immutable revision; lockfiles/bundles pin revisions + sha256. Native: llama.cpp optional; recommended `llama-server` + `openai-remote` path isolates native code outside the JVM. Observability recap (link to [Integrations](integrations.md)). GraalVM: core/planner avoid unnecessary reflection; `cortexj-declarative` uses `reflect.Proxy` and needs reachability metadata.

**Task 16 — `site-docs/docs/declarative.md`**
Outline: What it is · Semantics · Module · Native image.
Fact pack: `cortexj-declarative` ships a declarative interface API: `@ModelPrompt` annotation + dynamic proxy; `JsonPromptDecoder` with loud validation. Semantics (spec 48/49): prompt template + validation — **not** guaranteed decoding; validation failures are loud, not silent. No code example exists in verified sources — describe without one. Native image: requires reachability metadata for annotated interfaces. Scope today: prompt + validation; evolution beyond that is future work.

**Task 17 — `site-docs/docs/converters.md`**
Outline: Why convert · GGUF F32↔F16 · Safetensors→GGUF · Level C planning · Provenance.
Fact pack: deterministic conversions proposed by the planner when no direct artifact is viable; original artifacts stay immutable. GGUF float converter: F32↔F16, CLI (verbatim):

```bash
java -jar cortexj-cli-0.1.0-SNAPSHOT.jar convert ./model.gguf --to f16 --out ./converted
```

provenance manifest: sha256 of output + converted tensor counts; originals immutable. Safetensors→GGUF re-containerization: F32/F16/BF16/integer dtypes; `config.json`-derived metadata with honest provenance; verified end-to-end through Level C planning (planner routes a safetensors-only model through conversion).

**Task 18 — `site-docs/docs/providers.md`**
Outline: Runtime provider SPI · Repository provider SPI · Planner policies · The TCK.
Fact pack: all code blocks verbatim from docs/writing-runtime-provider.md, docs/writing-repository-provider.md, docs/writing-planner-policy.md: `MyRuntimeProvider` (descriptor with format/device/task/minJavaFeatureVersion/streamingSupported/nativeRuntime/threadSafety/maturity; probe honest with health values AVAILABLE/DEGRADED/UNAVAILABLE/MISCONFIGURED, cached per platform instance; evaluate → `RuntimeSupport.unsupported(PlanReason.negative(ReasonCode.FORMAT_UNSUPPORTED, …))` / `RuntimeSupport.full(...)`; open → re-probe or throw typed error; error mapping: `ModelLoadException` `CORTEXJ-LOAD-*`, `InferenceException` `CORTEXJ-INFER-*`, `NativeRuntimeException` `CORTEXJ-NATIVE-*` — never raw exceptions; lifecycle: `ModelRuntime.close()` releases native resources; `ThreadSafety.SESSION_SCOPED` → platform serializes per handle, `THREAD_SAFE` → concurrent). Registration files:

```text
src/main/resources/META-INF/services/io.cortexj.spi.runtime.RuntimeProvider
src/main/resources/META-INF/services/io.cortexj.spi.repository.ModelRepositoryProvider
```

Repository provider: `resolve()` MUST resolve to an immutable revision; honest `sizeBytes` (0 → conservative defaults); publish `Checksum` when available; use `context.tokenSupplier()`; offline → `CORTEXJ-MODEL-1003` (TCK asserts); `trustNote` for content you cannot vouch for. TCK: `RuntimeProviderContract` (descriptor sanity, probe reporting, evaluation reasons, registration), `RepositoryProviderContract` (precise `supports()`, immutable revision, typed unknown-reference errors, offline behavior). Policies: pure functions of (candidate, context); unit test with synthetic candidates; integrate with test providers. Existing implementations to study: `cortexj-repository-local`, `cortexj-repository-huggingface`, `cortexj-repository-http`.

**Task 19 — `site-docs/docs/reference/configuration-properties.md`**
Outline: settings table.
Fact pack (verified settings only):

| Setting | System property | Environment | Builder | Notes |
|---|---|---|---|---|
| Offline mode | `cortexj.offline=true` | `CORTEXJ_OFFLINE=1` | `.offline(true)` | uncached + offline → `CORTEXJ-MODEL-1003` |
| Remote base URL | `cortexj.remote.base.url` | `CORTEXJ_REMOTE_BASE_URL` | — | vLLM, llama-server, gateways; needs `allowRemoteRuntime(true)` |
| Remote API key | — | `CORTEXJ_REMOTE_API_KEY` | `.tokenSupplier(...)` | never logged |
| Hugging Face token | — | `CORTEXJ_HF_TOKEN` (also `HF_TOKEN` / `HUGGING_FACE_HUB_TOKEN`) | `.tokenSupplier(...)` | never logged |
| llama-server binary | `cortexj.llamacpp.server` | `CORTEXJ_LLAMACPP_SERVER` | — | standard prefixes probed as fallback |
| llama.cpp JNI lib | — | `CORTEXJ_LLAMACPP_LIB` | — | future JNI bridge |
| Trust policy | — | — | `.trustPolicy(TrustPolicy.*)` | `ALLOW_UNSIGNED_WITH_WARNING` default |
| Execution policy | — | — | `.executionPolicy(...)` | soft scoring / rejection |
| Executor | — | — | `.executor(...)` | injected `ExecutorService`, closed with platform |
| Allow remote runtime | — | — | `.allowRemoteRuntime(true)` | required for `openai-remote` |

Cache location: `~/.cortexj/cache`. Priority: builder > `cortexj.*` > `CORTEXJ_*`.

**Task 20 — `site-docs/docs/reference/api-stability.md`**
Outline: Tiers · Java 8/11 protection · Binary compatibility checks · What may change.
Fact pack (verbatim from docs/api-stability.md): four-tier table (Public API `io.cortexj`/`model`/`runtime`/`planner`/`hardware`/`stream`/`diagnostics`/`error` — broken only in majors, Java 8 bytecode baseline, no records in shared signatures; Stable SPI `io.cortexj.spi.*` — additive, TCK-verified; Experimental SPI `io.cortexj.spi.conversion` — may evolve during 1.0.x; Internal — no guarantee); Java 8/11 protection: shared modules `--release 8`, `CompletionStage` for async, own `TokenStream` for streaming, classes instead of records; `Flow.Publisher`/`Flux` adapters live in Java 11+/17+ modules and never leak into shared signatures; `compat` profile:

```bash
mvn -Pcompat verify -Dcortexj.api.baseline=1.0.0
```

skipped until a non-snapshot release exists; after 1.0.0 CI fails on binary-breaking Public API changes in minor/patch releases. What may change: internal packages, experimental SPI until promoted, JDK-profile platform modules (shared contract + `PlatformModule` SPI stay stable).

---

### Task 21: Local build + review

- [ ] **Step 1: Install MkDocs locally (if missing)**

```bash
python3 -m pip install --user mkdocs-material 2>&1 | tail -1 || python3 -m pip install --break-system-packages mkdocs-material 2>&1 | tail -1
```

- [ ] **Step 2: Strict build**

```bash
cd /Users/oleg/NikitaProjects/CortexJ-site && python3 -m mkdocs build --strict
```

Expected: `Documentation built in <n> seconds` with zero warnings (warnings fail the build). Fix anything it reports.

- [ ] **Step 3: Serve + user review**

```bash
cd /Users/oleg/NikitaProjects/CortexJ-site && python3 -m mkdocs serve --strict > /dev/null 2>&1 &
```

User opens `http://localhost:8000/docs/` (nav, search, dark mode, code copy) and the built landing at `http://localhost:8000/`. Fix flagged issues, then kill the server.

- [ ] **Step 4: Landing overlay check**

```bash
ls /Users/oleg/NikitaProjects/CortexJ-site/_site/index.html /Users/oleg/NikitaProjects/CortexJ-site/_site/docs/index.html
```

Expected: both exist (landing + docs home).

---

### Task 22: Commit + push (site repo)

```bash
cd /Users/oleg/NikitaProjects/CortexJ-site
git add -A
git commit -m "Docs: MkDocs Material documentation (17 pages, Diataxis structure) + build workflow + landing Docs link"
git push
```

(If push auth fails, one-time tokenized push as in the previous plan — never store the token.)

---

### Task 23: Verify live

- [ ] **Step 1: Workflow green**

```bash
sleep 45
TOKEN=$(git -C /Users/oleg/NikitaProjects/CortexJ remote get-url origin | sed -E 's|https://[^:]+:([^@]+)@.*|\1|')
curl -s -H "Authorization: Bearer $TOKEN" "https://api.github.com/repos/okostiukevych/CortexJ-site/actions/runs?per_page=1" | python3 -c "import json,sys; r=json.load(sys.stdin)['workflow_runs'][0]; print(r['name'], '|', r['status'], '|', r['conclusion'])"
```

Expected: `Pages | completed | success`.

- [ ] **Step 2: Live URLs**

```bash
curl -s -o /dev/null -w "docs %{http_code}\n" https://okostiukevych.github.io/CortexJ-site/docs/
curl -s -o /dev/null -w "landing %{http_code}\n" https://okostiukevych.github.io/CortexJ-site/
```

Expected: `docs 200`, `landing 200`.

---

### Task 24: Private repo — spec + plan commit

```bash
cd /Users/oleg/NikitaProjects/CortexJ
git add docs/superpowers/specs/2026-09-26-documentation-design.md docs/superpowers/plans/2026-09-26-documentation.md
git commit -m "Docs: documentation design spec + implementation plan (MkDocs Material on CortexJ-site, Diataxis structure)"
git push origin main
```

---

## Self-review notes

- Spec coverage: every page from the spec layout (17 files) has a task; workflow/mkdocs/landing changes covered (Tasks 1–3); verification matches spec (strict build, serve review, live checks).
- Placeholder discipline: all code blocks in fact packs are verbatim from verified sources; pages without verified examples (declarative) explicitly describe without code.
- Consistency: file paths in nav ↔ task file paths match; URL `/docs/` prefix produced by `docs/` level under `docs_dir: site-docs`.
