# ADR-019: Dataset abstraction vs adapters

Date: 2026-10-08. Status: accepted (0.x slice). Spec: v3.0 §§137, 151.

## Context

Lab/Train/Evaluation need one tabular abstraction, but the JVM already
has Tablesaw, Tribuo datasets, Arrow and DJL loaders. A from-scratch
dataframe engine would duplicate them; depending on all of them would
bloat every consumer.

## Decision

- Own thin contracts in `data-api` (`DataSource/Dataset/Frame/Column/
  Schema/Split/Fingerprint`) — immutable-by-default, Java 17, no IO
  dependencies.
- File formats via dedicated adapters (`data-csv`, `data-parquet`
  first) over mature libraries (Parquet via `parquet-hadoop`; the
  library never leaks into `data-api` signatures).
- Tablesaw/Tribuo/Arrow/JDBC/DJL adapters are follow-ups behind the
  same `DataSource` SPI, each its own module and dependency.
- Fingerprint = SHA-256 over schema + canonical cells; split/shuffle
  are seed-deterministic. Lazy/streaming datasets arrive with the
  first adapter that needs them, not upfront.

Scope-gate §159 answers: problem = one dataset identity from file to
training provenance; existing libs insufficient alone at the identity/
provenance layer (their objects carry no CortexJ fingerprint);
minimal solution = this slice; acceptance = spike G + KPI §124.4 file
coverage for CSV/Parquet now, rest per-adapter later; Runtime impact
= none; native/support cost = Parquet's transitive Hadoop client,
  isolated in `data-parquet`.

## Consequences

- `data-api` stays dependency-free; every new format is additive.
- Provenance (`datasetFingerprint`) flows into experiments unchanged.
