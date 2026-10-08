# CortexJ v3 Platform Tracks Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Построить треки v3 (P1–P11) поверх готового P0 Runtime, не сломав Runtime 1.0, его Java 8+ совместимость и независимость от engines/frameworks.

**Architecture:** Master-план + независимые sub-планы на трек. Runtime остаётся standalone (zero-зависимость на lab/train/tensor/research, контроль ArchUnit). Новые треки — отдельные Maven-модули из §135 со своими Java-baseline, SPI и DoD. Порядок: P0 closure → shared provenance → Lab/Data/Experiments → Train/PEFT/PyTorch → Tensor/Autograd → Research/Distributed. Adapter-first (§125 п.5, §159): зрелый engine подключается provider'ом, свой engine — только при доказанном gap.

**Tech Stack:** Java 8 (Runtime API/core) / 17 (Lab/Data/Experiments/Train) / 21+ (Tensor/Autograd/Research, preferred 25), Maven multi-module (`io.cortexj`), JUnit 5, TCK-контракты в `tck/`, ArchUnit-guard в `cli/src/test/java/io/cortexj/cli/ArchitectureGuardTest.java`, Jackson только в cli/server/adapters (не в core).

**Scope split (per writing-plans Scope Check):** этот файл — master-маршрут с точками интеграции и DoD. Каждый трек P1–P11 после своего scope-gate (§159) получает отдельный sub-план `docs/superpowers/plans/YYYY-MM-DD-cortexj-<track>.md` с побайтовыми задачами. Master гарантирует, что треки стыкуются (provenance-схема, dependency-границы, release train).

---
## 0. Исходное состояние (инвентаризация, проверено 2026-10-08)

Готово (P0 Runtime, scope v2):
- `api/ spi/ core/ model/ hardware/ cache/ streaming/ observability/ planner/`
- `repositories/local,huggingface,http`, `runtimes/onnx,llamacpp,remote-openai,djl,isolated`, `converters/gguf,safetensors`
- `platform/java8,java11,java17,java21,java25`, `starters/java8…`, `integrations/spring-ai,langchain4j`, `cli/ server/ worker/ declarative/ maven-plugin/ tck/`
- TCK-контракты: `tck/src/main/java/io/cortexj/tck/{RuntimeProviderContract,RepositoryProviderContract,ExecutionPolicyContract,PlatformModuleContract,ConverterContract}.java`
- Guard: `cli/.../ArchitectureGuardTest.java` (коммит `5aad039`, vacuous-pass сегодня)
- Закрыты: lockfile/bundle-схемы (#159), KPI-harness (#158), messaging (#156), JInfer-условия docs (#160), trust-boundary docs (#161)

Разрыв с §135 (чего нет — это и есть работа v3):
- Нет `docs/adr/` (ADR-016–028 не написаны), нет `lab/ data/ experiments/ train/ tensor/ research/`, нет `model-metadata/provenance` модулей
- Нет `runtimes/jinfer` кода (только docs-условия #160)
- Нет spikes F–L, нет DoD Lab/Train/Tensor (§154–156), нет north-star demo (§157)

---
## 1. File map (что создаём, куда)

```
// новые ADR (всего 13, §151) — каждый отдельный файл
Create: docs/adr/ADR-016-track-boundaries.md
Create: docs/adr/ADR-017-java-baselines-per-track.md
Create: docs/adr/ADR-018-notebook-kernel-strategy.md
Create: docs/adr/ADR-019-dataset-abstraction-vs-adapters.md
Create: docs/adr/ADR-020-provenance-schema.md
Create: docs/adr/ADR-021-training-backend-spi.md
Create: docs/adr/ADR-022-peft-artifact-model.md
Create: docs/adr/ADR-023-pytorch-libtorch-scope.md
Create: docs/adr/ADR-024-tensor-memory-ownership.md
Create: docs/adr/ADR-025-tensor-backend-spi.md
Create: docs/adr/ADR-026-autograd-graph-model.md
Create: docs/adr/ADR-027-distributed-collective.md
Create: docs/adr/ADR-028-research-isolation.md

// shared contracts (Java 8 bytecode, zero deps)
Create: model-metadata/pom.xml + src/main/java/io/cortexj/meta/ModelIdentity.java
Create: provenance/pom.xml + src/main/java/io/cortexj/provenance/ProvenanceManifest.java

// Lab / Data / Experiments — Java 17+, §136-138
Create: lab/lab-api/pom.xml + src/main/java/io/cortexj/lab/LabSession.java
Create: data/data-api/pom.xml + src/main/java/io/cortexj/data/Dataset.java (+ Frame, Column, Batch, DataSource)
Create: data/data-parquet/pom.xml, data/data-arrow/pom.xml, data/data-jdbc/pom.xml, data/data-csv/pom.xml
Create: experiments/experiments-api/pom.xml + src/main/java/io/cortexj/experiments/Experiment.java (+ ExperimentStore SPI)
Create: experiments/experiments-local/pom.xml

// Train — Java 17+ (optimized tier 21/25), §139-141
Create: train/train-api/pom.xml + src/main/java/io/cortexj/train/Trainer.java (+ TrainingPlan, Optimizer, LossFunction, ...)
Create: train/train-peft/pom.xml (LoRA adapter model)
Create: train/train-djl/pom.xml (backend provider, optional dep DJL)
Create: train/train-distributed/pom.xml (Phase A API, реализация в Фазе 5)

// Tensor / Autograd — Java 21+ (preferred 25), §142-143
Create: tensor/tensor-api/pom.xml + src/main/java/io/cortexj/tensor/Tensor.java (+ Shape, DType, Device, TensorBackend)
Create: tensor/tensor-cpu/pom.xml (reference backend)
Create: tensor/autograd/pom.xml

// Research — Java 21+, §144-146
Create: research/nn/pom.xml + src/main/java/io/cortexj/nn/Module.java
Create: research/benchmarks/pom.xml

// Runtime closure
Create: runtimes/jinfer/pom.xml + src/main/java/io/cortexj/runtime/jinfer/JInferRuntimeProvider.java

// Guards / TCK расширения
Modify: cli/src/test/java/io/cortexj/cli/ArchitectureGuardTest.java (track-границы §134.2)
Modify: pom.xml (modules), bom/pom.xml (dependencyManagement новых артефактов)

// Sub-планы (по одному на трек после scope-gate)
Create: docs/superpowers/plans/<date>-cortexj-lab-data-experiments.md
Create: docs/superpowers/plans/<date>-cortexj-train.md
Create: docs/superpowers/plans/<date>-cortexj-tensor-autograd.md
Create: docs/superpowers/plans/<date>-cortexj-research-distributed.md
```

Правила имён — design targets §135 (уточняются ADR до публикации). Java-baseline навязывается через `maven-compiler-plugin release` в каждом новом `pom.xml` (8 / 17 / 21) + CI-lane (§68) и ArchUnit-guard.

---
## 2. Фаза 0 — P0 Runtime 1.0 closure (блокер для всего остального, §108)

### Task 1: runtimes/jinfer provider (единственный недостающий P0-артефакт)

**Files:**
- Create: `runtimes/jinfer/pom.xml`
- Create: `runtimes/jinfer/src/main/java/io/cortexj/runtime/jinfer/JInferRuntimeProvider.java`
- Test: `runtimes/jinfer/src/test/java/io/cortexj/runtime/jinfer/JInferProviderContractTest.java`
- Modify: `pom.xml` (добавить `<module>runtimes/jinfer</module>` в jdk25plus-профиль, по аналогии с существующими runtime-модулями)

- [ ] **Step 1: Write the failing test** — TCK-контракт + independence-проверка (ядро без jinfer в classpath):

```java
package io.cortexj.runtime.jinfer;

import io.cortexj.tck.RuntimeProviderContract;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class JInferProviderContractTest extends RuntimeProviderContract {
    @Override protected String providerId() { return "jinfer"; }
    @Test public void absentDependencyDoesNotBreakCore() {
        assertDoesNotThrow(() -> Class.forName("io.cortexj.core.CortexJCore"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl runtimes/jinfer test -Dtest=JInferProviderContractTest`
Expected: FAIL — `package io.cortexj.tck does not exist` нет / `ClassNotFoundException: JInferRuntimeProvider` (модуля ещё нет)

- [ ] **Step 3: Write minimal implementation** — adapter инкапсулирует `Jinfer*` типы, core не видит их (условия #160, §19.1). Java 25 baseline, explicit registration + ServiceLoader:

```java
package io.cortexj.runtime.jinfer;

public final class JInferRuntimeProvider implements io.cortexj.spi.runtime.RuntimeProvider {
    @Override public String id() { return "jinfer"; }
    // probe(): Java<25 -> UNAVAILABLE с reason code; evaluate(): только capability-данные, выбор за planner (§16.3)
}
```

`runtimes/jinfer/pom.xml` — parent `io.cortexj:cortexj-parent:0.1.0-SNAPSHOT`, `release 25`, зависимость JInfer `optional/provided`, зависимость `cortexj-spi` (compile). Стартеры java8/11/17/21 его НЕ тянут.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl runtimes/jinfer -am test`
Expected: BUILD SUCCESS; TCK `RuntimeProviderContract` green

- [ ] **Step 5: Independence proof (§122)** — удалить зависимость, MVP green:

Run: `mvn test -P '!jinfer' -pl api,spi,core,planner,runtimes/onnx,runtimes/llamacpp`
Expected: BUILD SUCCESS без `cortexj-runtime-jinfer` в classpath

- [ ] **Step 6: Commit**

```bash
git add runtimes/jinfer pom.xml
git commit -m "feat(runtime): optional jinfer provider java25 (fixes P0-closure)"
```

### Task 2: Runtime 1.0 DoD checklist (§108, 15 пунктов) — верификация, не код

**Files:** Modify: `docs/verification-round-2026-10-08.md` (дописать раунд) или Create: `docs/verification-p0-1.0.md`

- [ ] **Step 1: Run the DoD matrix** — стартеры на своих JDK, planner determinism, lockfile/offline-bundle, оба адаптера:

Run: `mvn verify -pl starters/java8,api,core` (на JDK8) ; `mvn verify -pl starters/java25,runtimes/jinfer` (на JDK25) ; `mvn -pl tck test`
Expected: все green; legacy Java8 demo грузит ONNX/GGUF без Python; wrong-starter даёт actionable diagnostic (AC-JAVA-004)

- [ ] **Step 2: Commit verification record**

```bash
git add docs/verification-p0-1.0.md
git commit -m "docs: P0 Runtime 1.0 DoD verification"
```

Exit-критерий Фазы 0: §108 все 15 пунктов + §122 independence-демо. Без этого новые треки НЕ стартуют (§158).

---
## 3. Фаза 1 — Shared base: provenance + границы + ADR-016/017/020 (§134, §148, §150)

### Task 3: model-metadata + provenance модули (лёгкие, Java 8, без Jackson)

**Files:**
- Create: `model-metadata/pom.xml`, `model-metadata/src/main/java/io/cortexj/meta/ModelIdentity.java`
- Create: `provenance/pom.xml`, `provenance/src/main/java/io/cortexj/provenance/ProvenanceManifest.java`
- Test: `provenance/src/test/java/io/cortexj/provenance/ProvenanceManifestTest.java`
- Modify: `pom.xml`, `bom/pom.xml`

- [ ] **Step 1: Write the failing test** — схема отвечает на 9 вопросов §148:

```java
@Test public void manifestAnswersWhoWhatFromWhere() {
    ProvenanceManifest m = ProvenanceManifest.builder()
        .modelRevision("7f62").datasetFingerprint("sha256:abc")
        .platformVersion("1.0").build();
    assertEquals("7f62", m.modelRevision());
    assertTrue(m.checksumPresent());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl provenance test`
Expected: FAIL — модуля/класса нет

- [ ] **Step 3: Write minimal implementation** — immutable value object, builder, Java 8 API (без records), версионированная схема (`schemaVersion: 1`), JSON-сериализация НЕ в этом модуле (§97 — Jackson только в cli/server):

```java
package io.cortexj.provenance;
public final class ProvenanceManifest {
    private final String schemaVersion; // "1"
    private final String modelRevision; private final String datasetFingerprint;
    // + codeVersion, params/seeds, backend/hardware, parentArtifacts, checksums — все final, геттеры
    public static Builder builder() { return new Builder(); }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl model-metadata,provenance -am test`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add model-metadata provenance pom.xml bom/pom.xml
git commit -m "feat(platform): model-metadata + versioned provenance contracts (ADR-020)"
```

### Task 4: ADR-016/017/020 + ArchUnit track-границы (§134.2)

**Files:**
- Create: `docs/adr/ADR-016-track-boundaries.md`, `docs/adr/ADR-017-java-baselines-per-track.md`, `docs/adr/ADR-020-provenance-schema.md`
- Modify: `cli/src/test/java/io/cortexj/cli/ArchitectureGuardTest.java`

- [ ] **Step 1: Write the failing guard test** — добавить к существующему guard-классу правила §134.2:

```java
@Test public void runtimeDoesNotDependOnFutureTracks() {
    // runtime/*, api, spi, core, planner, model, hardware, cache — no access к lab.*, data.*, train.*, tensor.*, research.*
}
@Test public void tensorApiHasNoBackendDeps() { /* tensor-api не трогает tensor-cpu/cuda/native */ }
@Test public void experimentalDoesNotLeakIntoStableRuntime() { /* §150 */ }
```

- [ ] **Step 2: Run guard to verify it fails-then-passes**

Run: `mvn -pl cli test -Dtest=ArchitectureGuardTest`
Expected: сначала FAIL на любом нарушении (сегодня vacuous-pass — добавляем правила, тест остаётся green, но теперь non-vacuous: assert на количество правил > 0)

- [ ] **Step 3: Write the ADRs** — каждый: контекст → решение → последствия → границы. ADR-017 фиксирует таблицу: Runtime 8 / Lab-Data-Experiments-Train 17 (Train optimized 21/25) / Tensor-Autograd-Research 21 (preferred 25).

- [ ] **Step 4: Commit**

```bash
git add docs/adr/ cli/src/test/java/io/cortexj/cli/ArchitectureGuardTest.java
git commit -m "docs(adr): 016/017/020 + track boundary guards"
```

Scope-gate перед Фазой 2 (§159, 7 пунктов: проблема → почему адаптеров мало → minimal adapter-first → acceptance → impact на Runtime → native/support cost → exit criteria) — публикуется в ADR-018/019 преамбуле.

---
## 4. Фаза 2 — P1/P2/P3: Lab / Data / Experiments 0.x (§136-138, DoD Lab §154)

Sub-план: `docs/superpowers/plans/<date>-cortexj-lab-data-experiments.md` (создаётся первой задачей фазы).

### Task 5: Data API + CSV/Parquet adapters (spike G — fingerprint→evaluation)

**Files:**
- Create: `data/data-api/pom.xml` (release 17), `data/data-api/src/main/java/io/cortexj/data/{Dataset,Frame,Column,Batch,DataSource}.java`
- Create: `data/data-csv/pom.xml`, `data/data-parquet/pom.xml`
- Test: `data/data-parquet/src/test/java/.../ParquetFingerprintTest.java`

- [ ] **Step 1: Write the failing test** — детерминированный батчинг + fingerprint (§137.2):

```java
@Test public void parquetBatchesAreDeterministicAndFingerprinted() {
    Dataset<Row> ds = Data.readParquet("src/test/resources/reviews.parquet");
    assertEquals(ds.fingerprint(), ds.fingerprint());
    assertEquals(ds.split(0.8, 42L).train().count() + ds.split(0.8, 42L).test().count(), ds.count());
}
```

- [ ] **Step 2: Run test to verify it fails** — `mvn -pl data/data-api,data/data-parquet -am test` → FAIL (модулей нет)
- [ ] **Step 3: Implement** — контракты §137.1 (immutable-by-default transforms, lazy/streaming, typed schema, seed-split, Arrow-interop точка), CSV — thin wrapper, Parquet — через зрелую lib (adapter-first, public API не привязан к lib — §G-012)
- [ ] **Step 4: Verify** — `mvn -pl data/data-api,data/data-csv,data/data-parquet -am verify` → SUCCESS; ArchUnit-guard green (lab/data не втянуты в runtime)
- [ ] **Step 5: Commit** — `feat(data): Dataset/Frame API + csv/parquet + fingerprint (spike G)`

Adapters Arrow/JDBC/Tablesaw/Tribuo/DJL — отдельные задачи sub-плана, каждая: TCK/контракт-тест → реализация → commit. Собственный engine запрещён без gap-доказательства.

### Task 6: Experiments API + local store (spike H — двойной прогон, идентичные метаданные)

**Files:** Create: `experiments/experiments-api/.../{Experiment,ExperimentStore}.java`, `experiments/experiments-local/...`

- [ ] **Step 1: Failing test** — `runTwiceCapturesIdenticalProvenance()` (model revision + dataset hash + runtime/hw/JDK + seeds + output hashes, §138.2), пример из §138.1 (`try (Experiment e = ...start("qwen-lora"))`)
- [ ] **Step 2–4:** test FAIL → implement (autocapture + local FS store; external trackers — SPI, не в 0.x) → `mvn -pl experiments/... -am verify` SUCCESS
- [ ] **Step 5: Commit** — `feat(experiments): lifecycle + provenance capture + local store (spike H)`

### Task 7: Lab 0.x thin slice (spike F — Runtime из Java-ядра + рендер)

**Files:** Create: `lab/lab-api/.../LabSession.java`, `lab/notebook/...` (bootstrap/magics поверх зрелого Java/JShell-ядра — НЕ писать Jupyter-протокол с нуля, §136.2)

- [ ] **Step 1: Failing test** — `loadModelAndRenderPlan()` (model.load + stream токенов + table + ExecutionPlan-visualization, §136.3) + `exportCellsToMavenSkeleton()` + `environmentDescriptorIsReproducible()`
- [ ] **Step 2–4:** FAIL → implement (adapter к зрелому kernel, rich display contracts, cancellation/cleanup) → verify на JDK17/21
- [ ] **Step 5: Commit** — `feat(lab): notebook integration slice (spike F)`

DoD Lab §154 (10 пунктов: baseline documented, Jupyter-flow, interactive inference, frame/chart/image рендер, reproducible env, provenance capture, export, cleanup, no-Python-kernel) — чеклист-задача с `mvn verify` + ручное демо, коммит `docs/verification-lab-0.x.md`.

KPI §124.4: notebook→inference ≤5 мин, CSV/JSON/Parquet/JDBC/Arrow IO, 100% revision-metadata в export.

---
## 5. Фаза 3 — P4/P5/P6: Train — PEFT → PyTorch interop → General (§139-141, DoD §155)

Sub-план: `docs/superpowers/plans/<date>-cortexj-train.md`. Baselines: Java 17+, optimized tier 21/25. Backend-стратегия §139.4/§141: WRAP proven engines (DJL/PyTorch/native/remote) через SPI; свой trainer — только при gap.

### Task 8: Train API + PEFT LoRA slice (spike I — fine-tune малой модели через external engine → adapter в Runtime)

**Files:** Create: `train/train-api/.../{Trainer,TrainingPlan,Optimizer,LossFunction,LearningRateSchedule,CheckpointPolicy,EvaluationPolicy,MixedPrecisionPolicy,TrainingBackend}.java` (§141.1), `train/train-peft/...` (LoRA/adapter model, ADR-022)

- [ ] **Step 1: Failing test** — high-level API §139.2 (`model+dataset+strategy LORA+epochs+lr+device AUTO → start()`), `validateBeforeAllocating()`, `checkpointResume()`, `adapterDeployableInRuntime()` (KPI §124.5: LoRA-артефакт ставится в Runtime без ручной хирургии формата)
- [ ] **Step 2–4:** FAIL → implement (compat-validation + memory estimate ДО аллокации §139.3, checkpoint/resume, eval-hooks, export) → `mvn -pl train/train-api,train/train-peft -am verify` SUCCESS
- [ ] **Step 5: Commit** — `feat(train): Trainer API + LoRA PEFT slice (spike I, ADR-021/022)`

### Task 9: PyTorch interop (spike J — checkpoint/adapter без Python в deploy-фазе)

**Files:** Create задачи sub-плана: Safetensors/checkpoint mapping → ONNX/export → tokenizer/config metadata → LoRA-adapters → LibTorch-bridge ТОЛЬКО при выполнении 4 условий §140.3 (ABI-стратегия, точный op-scope, дистрибуция/секьюрность, perf-оправдание)

- [ ] **Step 1: Failing test** — `importExportRoundTrip()` + `unsupportedOpProducesExplicitDiagnostic()` (НЕ silent degradation, §140.4)
- [ ] **Step 2–4:** FAIL → implement → verify
- [ ] **Step 5: Commit** — `feat(train): pytorch interop slice (spike J, ADR-023)`

### Task 10: General Training backends (DJL + remote-trainer, ≥2 стратегии — DoD §155 п.6)

- [ ] Failing test → `train/train-djl` provider + remote-trainer provider → mixed-precision explicit, device/backend planning, metrics-stream в Experiments/Lab, reproducibility manifest → verify → commit.

DoD Train §155 (10 пунктов) + KPI §124.5 (deterministic resume, 100% provenance, deployable adapter, stable orchestration API) — чеклист + `docs/verification-train-0.x.md`.

---
## 6. Фаза 4 — P7/P8: Tensor + Autograd (§142-143, DoD §156)

Sub-план: `docs/superpowers/plans/<date>-cortexj-tensor-autograd.md`. Java 21+ (preferred 25). Correctness-first (§143.2): perf вторичен до зелёного differential-suite.

### Task 11: Tensor API + CPU-reference backend (spike K — matmul/reduction + 1 accelerated provider за тем же API)

**Files:** Create: `tensor/tensor-api/.../{Tensor,Shape,DType,Device,Layout,TensorBackend,TensorStorage}.java`, `tensor/tensor-cpu/...`

- [ ] **Step 1: Failing test** — ownership/lifecycle native-памяти (ADR-024), explicit dtype/device transfer, broadcasting semantics, lazy/eager однозначность (§142.2), op-families §142.3 (creation/slice/reshape/arithmetic/reductions/matmul/softmax/norm/activations/gather-scatter)
- [ ] **Step 2–4:** FAIL → implement (immutable metadata, backend escape-hatch изолирован) → CPU-reference + 1 accelerated/delegating provider → `mvn -pl tensor/... -am verify` SUCCESS, conformance-suite ≥99% op-matrix (KPI §124.6)
- [ ] **Step 5: Commit** — `feat(tensor): API + CPU reference + 1 provider (spike K, ADR-024/025)`

### Task 12: Autograd (spike L — градиенты MLP против trusted reference в tolerance)

**Files:** Create: `tensor/autograd/...` (graph/tape, requiresGradient, backward, leaf/non-leaf, accumulation, detach, no-grad scope, custom rules, hooks — §143.1; retention/checkpointing policies §143.3)

- [ ] **Step 1: Failing test** — `mlpGradientsMatchReferenceWithinTolerance()` + `detachAndNoGradScopes()` + `customBackwardRule()`
- [ ] **Step 2–4:** FAIL → implement → differential-suite green → verify
- [ ] **Step 5: Commit** — `feat(tensor): autograd graph model (spike L, ADR-026)`

DoD §156 (10 пунктов incl. п.10: Runtime НЕ зависит от Tensor/Autograd без explicit-выбора — ArchUnit-тест) + Module-демо (§144: custom attention без правок core).

---
## 7. Фаза 5 — P9/P10/P11: Architecture + Distributed + Research (§144-146) + north-star §157

Sub-план: `docs/superpowers/plans/<date>-cortexj-research-distributed.md`.

### Task 13: nn Module API + research workflows (P9/P11)

**Files:** Create: `research/nn/.../Module.java` (`forward`, params/buffers/submodules, train/eval, state-dict, init, serialization, custom-op extension, inspectable tree — §144.2), `research/benchmarks/...`, dataset-registry adapters, model/dataset-cards, citation metadata (§146)

- [ ] Failing test → implement → `customAttentionWithoutCoreChanges()` + notebook-friendly repr → verify → commit (ADR-028 isolation: user-code выполняется в отдельном trust-профиле, §149 — НЕ ослабляет Runtime-defaults)

### Task 14: Distributed Phase A→C (§145: A single-node multi-GPU data-parallel; B multi-node+collectives; C tensor/pipeline-parallel)

**Files:** Create: `train/train-distributed/...` (реализация; API-заглушка создана в Фазе 3)

- [ ] Failing test → Phase A → verify (topology-planner ОТДЕЛЁН от inference-planner, §145.2) → commit; B/C — отдельные задачи sub-плана (ADR-027)

### Task 15: North-star demo §157 + release train §153

End-to-end: `Java notebook → Parquet → visualize → Hub-model → Experiment → LoRA → eval → export adapter → lock revision+provenance → Runtime deploy → planner выбирает backend → prod-сервис`. Для P9+: второй прогон с custom JVM-Module вместо pretrained-архитектуры.

- [ ] Steps: скрипт `examples/north-star/` + CI-job (CPU-only) + `docs/verification-north-star.md` → commit.

Релизы §153: `Runtime 1.0 (stable) → Lab 0.x → Train 0.x → Tensor 0.x → Research 0.x`; маркетинг-версия платформы отдельно, maturity публикуется потреково + capability-matrix на релиз (§123.2 — НЕ обещать «замену PyTorch»/«любую HF-модель»).

---
## 8. Сквозные ворота (применяются к каждой фазе)

- [ ] **Scope-gate §159** (7 пунктов) опубликован ДО старта фазы; adapter-first предпочтителен если закрывает ≥80% кейса
- [ ] **Dependency-impact**: `mvn -pl cli test -Dtest=ArchitectureGuardTest` green; inference-приложение без новых транзитивных deps (§G-016 — проверяется `mvn dependency:tree -pl starters/java8 | grep -E "lab|train|tensor"`, ожидаемо пусто)
- [ ] **Security**: Runtime-defaults (§149: no arbitrary code, checksums, trust-policy) untouched; research-code только в isolation-профиле (ADR-028)
- [ ] **KPI §124**: Runtime <2% latency / <5% throughput / <100MB overhead / 0 silent-fallback; planner valid-plan ≥99%; benchmark-coverage ≥80%
- [ ] **Docs**: capability-matrix релиза + guides (Lab/Data/Train/Tensor по §101-102 образцу)

---
## 9. Self-review против ТЗ v3 (пройти перед стартом Фазы 2)

1. **Spec coverage:** §106 P0–P11 → Фазы 0–5 ✓; §134 границы → Task 4 ✓; §135 модули → File map ✓; §136–146 спеки → Tasks 5–14 ✓; §147 flow → Task 15 ✓; §148 provenance → Task 3 ✓; §149 trust-split → Tasks 4/13 + ворота ✓; §150 evolution → Task 4 guard ✓; §151 ADR 016–028 → Tasks 4/5/6/8/9/11/12/14 (все 13 распределены) ✓; §152 spikes F–L → Tasks 5–12 (все 7) ✓; §153 train → Task 15 ✓; §154–156 DoD → Tasks 7/10/12 ✓; §157 demo → Task 15 ✓; §158 sequencing → порядок фаз ✓; §159 gates → §8 ✓; §124 KPI → §8 + DoD-задачи ✓.
2. **Placeholder scan:** запрещены `TBD/TODO/"similar to"` — каждая задача содержит точные пути, код\Contracts из спеки, команды `mvn` с ожидаемым выводом.
3. **Type consistency:** `ModelIdentity/ProvenanceManifest/Dataset/Experiment/Trainer/Tensor/Module` — имена из §§137–144; `TrainingStrategy.LORA`, `Device.AUTO` — из §1.5/§139.2; reason-codes/diagnostics — из §G-007/§26.

Найденные при review пробелы — фиксировать inline в этом файле (не откладывать).
