# ADR-025: Tensor backend SPI

Date: 2026-10-08. Status: accepted (0.x). Spec: v3.0 §142.4.

## Context

The tensor programming model must be engine-neutral: a reference CPU
backend for correctness, accelerated backends delegating to vector
instructions or external engines later.

## Decision

- `cortexj-tensor-api` declares `Tensor`, `Shape`, `DType`, `Device`
  and the `TensorBackend` SPI (id, capability check, create).
- `cortexj-tensor-cpu` is the scalar reference: correctness first,
  simple loops, no native deps.
- `cortexj-tensor-vector` is the first accelerated provider:
  `jdk.incubator.vector` SIMD matmul/reductions behind the same API
  (Java 21+; the incubator module stays isolated in this provider —
  never in api signatures).
- Conformance: every op family is executed through both backends with
  parity assertions (tolerance per dtype) — the op-matrix suite grows
  with each new op; the KPI target is ≥99% conformance for the
  supported op matrix.

Scope-gate §159 answers: problem = engine-neutral tensor math on JVM;
existing engines (jota/DJL) insufficient alone for a stable
correctness-first contract owned by CortexJ; minimal solution =
reference + one vector provider; acceptance = parity suite; Runtime
impact = none; native cost = zero (pure Java + JDK incubator).

## Consequences

- A GPU/native backend is a new provider module, never an api change.
- The vector backend is honest about its incubator status in its
  descriptor metadata.
