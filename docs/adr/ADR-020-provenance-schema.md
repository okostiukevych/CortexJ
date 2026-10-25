# ADR-020: Provenance schema

Date: 2026-10-08. Status: accepted. Spec: v3.0 §§138.2, 148.

## Context

Reproducibility is a platform feature (G-014): Lab, Train and Runtime
must describe "what produced this artifact" identically, without
dragging a storage implementation or Jackson into the contract.

## Decision

- `cortexj-model-metadata`: `ModelIdentity` — canonical model id
  (`hf:owner/repo`, `file:...`, ...) plus the resolved immutable
  revision (null while unresolved).
- `cortexj-provenance`: `ProvenanceManifest` — immutable value object
  answering the nine §148 questions: producer (platform version),
  model revision, dataset fingerprint, code/platform version,
  parameters, seeds, backend + hardware identity, parent artifacts,
  checksums. `schemaVersion` is `"1"`; schema evolves independently of
  any store (§148: versioned separately).
- Both modules: Java 8 bytecode, zero runtime dependencies,
  Jackson-free (§97 — serialization lives in cli/server adapters).
- Missing data is explicit (empty maps / null), never fabricated:
  no fake compatibility (§140.4 applies by analogy).

## Consequences

- Experiment store (§138.3), training jobs (§139.3) and the model
  lockfile all embed this manifest instead of inventing their own.
- Schema v2 requires a new ADR and a migration note, never a silent
  field change.
