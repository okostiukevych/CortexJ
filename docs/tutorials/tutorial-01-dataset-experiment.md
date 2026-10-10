# Tutorial 1 — first dataset and experiment (30 minutes)

You load a CSV, fingerprint it, capture a reproducible experiment and
read the run back. Every snippet runs against the tested modules
(`cortexj-data-csv`, `cortexj-experiments-local`).

## 1. Read the data

```java
Frame frame = CsvFrame.read(Path.of("reviews.csv"));
System.out.println(frame.rowCount());          // rows
System.out.println(frame.schema().names());    // [id, stars, text]
```

Types are inferred (`LONG`/`DOUBLE`/`BOOLEAN`/`STRING`), empty cells
are missing — see `CsvFrameTest` for the contract.

## 2. Fingerprint it

```java
String fingerprint = Fingerprint.ofFrame(frame);
```

Same data through CSV, Parquet, Tablesaw or Arrow yields the same
fingerprint (cross-checked in `TablesawFrameTest`). The fingerprint
is the dataset identity from here on.

## 3. Split deterministically

```java
FrameDataset dataset = new FrameDataset(frame);
Split<Dataset<List<String>>, Dataset<List<String>>> split =
        dataset.split(0.8, 42L);   // same seed -> same split, always
```

## 4. Capture the experiment

```java
ExperimentStore store = new LocalExperimentStore(Path.of("experiments"));
try (Experiment experiment = store.start("my-first-run", 42L, fingerprint,
        ModelIdentity.of("company/model", "rev-1"), "reference/0.1")) {
    experiment.parameter("learningRate", "2e-4");
    experiment.metric("validation.loss", "0.42");
}
ExperimentRecord record = store.list().get(0);
// record.provenance() carries model revision, dataset hash, JDK, seeds
```

## 5. Read it back

```java
ExperimentRecord same = store.get(record.id());
```

Runs live in `runs/<id>/` (`manifest.properties`,
`metrics.properties`, `artifacts/`). Artifacts are copied, never
moved. The full double-run reproducibility proof is
`LocalExperimentStoreTest`.
