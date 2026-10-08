# CortexJ Lab/Data/Experiments 0.x Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Incubate P1/P2/P3 so a JVM team can load a dataset, inspect it, run a reproducible experiment and hand results to Runtime — without Python, without touching Runtime internals.

**Architecture:** Three independent `release=17` module groups behind versioned ADR decisions. Data owns tabular contracts + file adapters (mature Parquet lib behind our API, never leaking into signatures). Experiments owns lifecycle + a filesystem store with the shared `ProvenanceManifest` embedded. Lab owns a JShell-backed interactive session (JDK-built-in, zero deps) + display contracts; the full Jupyter-kernel adapter is an explicit follow-up (ADR-018). Guards from Phase 1 stay green throughout.

**Tech Stack:** Java 17, Maven (`io.cortexj`), JUnit 5, Apache Parquet (`org.apache.parquet:parquet-hadoop`), `jdk.jshell` (JDK-built-in), shared `cortexj-provenance`.

---

## File map

```text
Create: docs/adr/ADR-018-notebook-kernel-strategy.md
Create: docs/adr/ADR-019-dataset-abstraction-vs-adapters.md

Create: data/data-api/pom.xml (release 17)
        data/data-api/src/main/java/io/cortexj/data/{DataSource,Dataset,Frame,Column,Schema,ColType,Split}.java
        data/data-api/src/test/.../FrameTest.java
Create: data/data-csv/pom.xml + src/main/java/io/cortexj/data/csv/CsvFrame.java (+ test + resource reviews.csv)
Create: data/data-parquet/pom.xml + src/main/java/io/cortexj/data/parquet/ParquetFrame.java (+ test + resource reviews.parquet)
Create: data/data-api/.../Fingerprint.java (SHA-256 over schema+rows)

Create: experiments/experiments-api/pom.xml (release 17)
        experiments/experiments-api/src/main/java/io/cortexj/experiments/{Experiment,ExperimentStore,ExperimentRecord}.java
Create: experiments/experiments-local/pom.xml + .../LocalExperimentStore.java (+ test)
        (store layout: runs/<id>/manifest.properties + metrics.properties + artifacts/)

Create: lab/lab-api/pom.xml (release 17, deps: data-api, experiments-api, planner)
        lab/lab-api/src/main/java/io/cortexj/lab/{LabSession,SnippetResult,Displayable,Display,EnvironmentDescriptor}.java (+ tests)
```

---

## Task 1 (#166): Data API + CSV/Parquet + fingerprint (spike G)

**Files:** `data/*` from the map above.

- [ ] **Step 1: failing test** — `CsvFrameTest.readsTypedCsv`: `reviews.csv`
  (`id,stars,text`) → `Frame` with `LONG/STRING` columns, `rowCount()==3`,
  `head(2)` keeps schema; `Fingerprint.of(frame)` stable across two reads.
- [ ] **Step 2: run** — `mvn -pl data/data-api,data/data-csv test`
  Expected: FAIL (no classes).
- [ ] **Step 3: implement** — `Schema` (names + `ColType{STRING,LONG,DOUBLE,BOOLEAN}`),
  `Column<T>` (name/type/size/get), `Frame` (schema/rowCount/head/select),
  `Dataset<T>` (`iterator/count/fingerprint/batch/split`), `Split<A,B>`
  (`train/test`), `Fingerprint` (SHA-256 hex over `name:type` + `\0`-joined
  cells; null → `<null>`). CSV: RFC-4180-ish parser (quotes + commas),
  type inference LONG→DOUBLE→STRING, empty→null. Parquet: `parquet-hadoop`
  `ParquetReader<GenericRecord>` via `GroupReadSupport`, type map
  INT32→Long, INT64→Long, FLOAT/DOUBLE→Double, BOOLEAN→Boolean,
  BINARY→String, deterministic row order (file order, no readahead
  shuffle). Deterministic split: index shuffle with `new Random(seed)`.
- [ ] **Step 4: verify** — same `mvn` command → PASS; spike G scenario
  (`readParquet → batch(32) → fingerprint → same fingerprint twice`) as
  `ParquetDeterminismTest`.
- [ ] **Step 5: commit** — `feat(data): Dataset/Frame API + csv/parquet + fingerprint (fixes #166)`

## Task 2 (#167): Experiments API + local store (spike H)

**Files:** `experiments/*` from the map above.

- [ ] **Step 1: failing test** — `LocalExperimentStoreTest.doubleRunCapturesIdenticalProvenance`:
  two runs with same name/params/dataset-fingerprint produce identical
  captured fields (model revision, dataset hash, JDK, OS, backend versions,
  seeds) and distinct run ids; `store.list()` finds both.
- [ ] **Step 2: run** — `mvn -pl experiments/experiments-api,experiments/experiments-local test`
  Expected: FAIL.
- [ ] **Step 3: implement** — `Experiment extends AutoCloseable`
  (`parameter/metric/artifact/provenance`), `ExperimentStore`
  (`start/list/get`), `ExperimentRecord` (id/name/params/metrics/artifacts
  + embedded `ProvenanceManifest`). `LocalExperimentStore(root)`:
  `runs/<id>/manifest.properties`, `metrics.properties`, `artifacts/`
  (copies, never moves). Auto-capture helper fills JDK/OS/arch,
  `cortexj` version (unknown-safe), seeds. No Jackson — `Properties`
  files only.
- [ ] **Step 4: verify** — PASS.
- [ ] **Step 5: commit** — `feat(experiments): lifecycle + local store (fixes #167)`

## Task 3 (#168): Lab 0.x thin slice + DoD Lab (spike F)

**Files:** `lab/*` from the map above.

- [ ] **Step 1: failing test** — `LabSessionTest.jshellRoundTrip`: `eval("1 + 2")`
  → value `"3"`; `eval` of unknown symbol → errors non-empty, no throw;
  `describeEnvironment()` reports real `java.version`; `display(frame)`
  renders a text table containing headers; `exportProjectSkeleton(dir, id)`
  writes `pom.xml/README.md/Main.java` containing the id.
- [ ] **Step 2: run** — `mvn -pl lab/lab-api test` Expected: FAIL.
- [ ] **Step 3: implement** — `LabSession implements AutoCloseable`
  over `jdk.jshell.JShell` (inherits process classpath, so
  `CortexJ` snippets work wherever the session runs): `eval`,
  `Displayable{mimeType,content}`, `Display.text/table/plan`
  (plan renderer takes `io.cortexj.planner.ExecutionPlan` text form),
  `EnvironmentDescriptor` (JDK/OS/arch/cortexj-version/timestamp, all
  read live), `exportProjectSkeleton` (starter-java17-based pom +
  README + `Main.java` sample). Jupyter-kernel adapter stays a
  follow-up per ADR-018 (explicit, not a silent gap).
- [ ] **Step 4: verify** — PASS; DoD Lab §154 recorded in
  `docs/verification-lab-0.x.md` (10 items, honest gaps marked).
- [ ] **Step 5: commit** — `feat(lab): JShell session + display + export (fixes #168)`

## Gates (every task)

- [ ] `ArchitectureGuardTest` green (lab/data/experiments never in runtime).
- [ ] No Jackson/Jupyter/heavy deps in `*-api` modules (`mvn dependency:tree` check).
- [ ] Scope-gate §159 answers recorded in the matching ADR preamble.
