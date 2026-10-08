# ADR-023: PyTorch interoperability scope

Date: 2026-10-08. Status: accepted (0.x). Spec: v3.0 §140.

## Context

Model creation stays Python/PyTorch-first; CortexJ must interoperate
without pretending to be PyTorch (no fake compatibility).

## Decision

Interoperability priority for the 0.x slice:

1. Safetensors container read/write (pure Java) — adapters and
   checkpoints travel both ways without Python in the deploy phase.
2. LoRA adapter artifact exchange (`adapter_model.safetensors` +
   `adapter_config.json` sidecar).
3. Tokenizer/config metadata mapping stays with the existing
   repository/artifact subsystems.
4. ONNX/export compatibility rides the existing converter modules.

A LibTorch/native bridge is OUT of scope until all four conditions
hold: sustainable ABI/version strategy, precisely stated supported
operations, acceptable native distribution/security model, measured
performance justification. Unsupported ops, graph semantics or
Python-only model code produce explicit typed diagnostics, never
silent degradation.

Scope-gate §159 answers: problem = checkpoint/adapter round-trip
without Python; existing JVM safetensors readers are insufficient
because none ship a writer + adapter naming contract; minimal
solution = items 1–2; acceptance = round-trip test; Runtime impact =
none; native/support cost = zero (pure Java).

## Consequences

- The safetensors module owns the container format; the PEFT module
  owns the adapter semantics; neither depends on the other's internals
  beyond the public contracts.
