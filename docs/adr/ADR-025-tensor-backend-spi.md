# ADR-025: Tensor backend SPI

Date: 2026-10-08. Status: accepted (0.x).

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

Scope-gate answers: problem = engine-neutral tensor math on JVM;
existing engines (jota/DJL) insufficient alone for a stable
correctness-first contract owned by CortexJ; minimal solution =
reference + one vector provider; acceptance = parity suite; Runtime
impact = none; native cost = zero (pure Java + JDK incubator).

## Consequences

- A GPU/native backend is a new provider module, never an api change.
- The vector backend is honest about its incubator status in its
 descriptor metadata.
- Honest benchmark finding (2026-10-08, 128x128 F32, Apple arm64):
 the JIT-auto-vectorized scalar reference outperforms the Vector API
 path (~6x) — the C2 compiler is very strong on simple loops. The
 vector backend exists for the SPI contract and correctness parity;
 kernel tuning beyond this is a declared follow-up, never a perf
 claim (raw backend numbers are not attributed to the platform).
