# ADR-003: RuntimeProvider SPI

Date: 2026-10-25. Status: accepted (ratified retroactively). Spec: v3.0 §§16, 17, 20.

## Context

Execution engines differ wildly (onnxruntime, llama.cpp bindings, JInfer,
remote services). Embedding engine knowledge in the core would couple release
trains and forbid third-party runtimes.

## Decision

Engines implement `io.cortexj.spi.runtime.RuntimeProvider` and are discovered
via **`java.util.ServiceLoader`** — no core registry, no core-module edits. A
provider declares capabilities through `RuntimeDescriptor` (formats, devices,
tasks, Java floor, thread-safety, maturity), and the planner consumes the
descriptor, never the engine class. `tck/RuntimeProviderContract` pins the
expected behaviour every provider must satisfy.

## Consequences

- Adding a runtime = one new module + `META-INF/services` entry (demonstrated
  by `examples/.../plugin/CustomRuntimeProvider`).
- The planner's inputs stay data-shaped: descriptors, not engine internals.
- Provider regressions fail in CI via the TCK contract, not in production.
