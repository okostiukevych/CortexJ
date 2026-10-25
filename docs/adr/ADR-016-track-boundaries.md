# ADR-016: Product-track dependency boundaries

Date: 2026-10-08. Status: accepted.

## Context

CortexJ v3 grows from one Runtime product into platform tracks
(Lab / Data / Experiments / Train / Tensor / Autograd / Research),
but the production inference path must stay standalone: a user who
needs only inference must never pay the dependency cost of
Lab/Data/Training/Tensor/Autograd (G-016), and Runtime releases must
never wait for research tracks.

## Decision

Module graph (enforced, not advisory):

```text
runtime/* MUST NOT depend on lab/*, data/*, experiments/*,
 train/*, tensor/*, autograd/*, research/*
lab/* MAY depend on Runtime API, Data API, Experiments API
train/* MAY depend on Data/Experiments/Tensor API (engine deps stay optional)
tensor-api MUST NOT depend on any concrete accelerator backend
autograd depends on Tensor API, never on Runtime
research MAY depend on Tensor/Autograd/Train/Lab
shared (model-metadata, provenance): lightweight, dependency-free,
 usable from every track including Runtime
```

`experimental.*` packages never appear in stable Runtime signatures
.

## Consequences

- `ArchitectureGuardTest.runtimeNeverDependsOnFutureTracks` fails the
 build on the first violation (non-vacuous from the first Lab/Data
 module on).
- New tracks arrive as separate Maven modules (names are design
 targets until their own ADR publishes them).
- A scope-gate is required before each new track: without it,
 adapter-first wins by default.
