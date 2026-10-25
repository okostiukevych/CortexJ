# ADR-022: PEFT artifact model

Date: 2026-10-08. Status: accepted (0.x). Spec: v3.0 §139.

## Context

LoRA/QLoRA adapters are the first trainable artifacts the platform
produces and consumes. They must flow into Runtime deployment without
manual format surgery and interoperate with the PyTorch ecosystem.

## Decision

- `cortexj-train-peft` owns:
  - `LoRAConfig` — rank, alpha, dropout, target module names;
    immutable;
  - `LoRAAdapter` — the trained low-rank matrices (A: rank×in, B:
    out×rank) plus config and provenance;
  - merge/unmerge math `W' = W + (alpha/rank)·B·A` in pure Java,
    deterministic and unit-tested;
  - adapter file import/export in the safetensors container format
    (PyTorch ecosystem compatible; pure Java, no Python).
- Adapter tensors use the standard `adapter_model.safetensors` naming
  and ship a `adapter_config.json`-compatible sidecar; both produced
  and consumed.
- Provenance embedded via the shared `ProvenanceManifest`; output
  checksums mandatory.

## Consequences

- An adapter produced by CortexJ loads in Runtime via the standard
  artifact path; no private format as the main path.
- Schema changes require a new ADR, never a silent rename.
