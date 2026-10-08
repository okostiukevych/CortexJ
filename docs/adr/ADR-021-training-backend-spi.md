# ADR-021: Training backend SPI

Date: 2026-10-08. Status: accepted (0.x). Spec: v3.0 §§139.4, 141.

## Context

Training code must stay backend-neutral; proven engines are wrapped,
never reimplemented (integrate-first). LoRA/PEFT fine-tuning is the
first production use case.

## Decision

- `cortexj-train-api` declares `TrainingBackend` and the job contracts.
  A backend reports compatibility through descriptors and probe-style
  evaluation; the high-level `TrainingJob` API validates model/backend/
  strategy compatibility and memory BEFORE allocating resources
  (fail-fast, same policy as runtime planning).
- Backends ship as separate optional modules:
  - `cortexj-train-djl` — wraps DJL training capability, honest
    `unavailable` when no training engine is installed;
  - `cortexj-train-remote` — calls an OpenAI-compatible fine-tuning
    endpoint, probe-gated like the remote runtime.
- Unsupported model/backend/strategy combinations fail typed before any
  expensive execution. Metrics stream to Experiments/Lab; provenance
  uses the shared `ProvenanceManifest`.

Scope-gate §159 answers: problem = JVM fine-tuning without Python;
existing engines insufficient alone only at the orchestration layer
(no unified adapter validation/checkpoint/resume); minimal solution =
contracts + two probe-gated backends; acceptance = DoD Train items;
Runtime impact = none; native cost = DJL PyTorch engine, isolated in
its provider module.

## Consequences

- A new engine is a new provider, not a core change.
- Deterministic resume and adapter export are backend contract tests.
