# ADR-011: llama.cpp binding approach

Date: 2026-10-25. Status: accepted (ratified retroactively). Spec: v3.0 §§18, 36.

## Context

GGUF quantisations are the widest CPU inference surface; llama.cpp's C API is
the practically supported FFI surface. Loaders must support per-invocation
session lifetimes and clean unload semantics.

## Decision

`runtimes/llamacpp` wraps llama.cpp via prebuilt Natives packaged per
OS/arch (ADR-005), declares GGUF only, session-scoped thread-safety and CPU
device support in its descriptor, and reports probe health (library present,
version compatible) for planner scoring. When in-process natives are
unacceptable, the `isolated` runtime path hosts the engine separately.

## Consequences

- GGUF availability is a descriptor fact, checkable without loading.
- Missing natives produce loud, actionable errors (verified message tests).
- Streaming and structured-output support declared in descriptor, not guessed.
