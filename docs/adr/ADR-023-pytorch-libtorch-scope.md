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

## Spike result (2026-10-10): the native path is proven

A throwaway spike (kept outside the repo) verified the mechanics:
libtorch 2.7.1 for macOS arm64 + a 60-line `extern "C"` shim
(create/add/read/destroy over ATen F32 tensors) + Java FFM
(`Linker`/`SymbolLookup`, `--enable-native-access`) computes
`[1,2,3,4]+[5,6,7,8] = [6,8,10,12]` exactly. Two toolchain notes:
Apple clang needs `-Wno-invalid-specialization` for the libtorch
headers; the shim's `@rpath` deps need `DYLD_FALLBACK_LIBRARY_PATH`
at load time.

The spike proves the mechanics, not the product: the four
conditions above still gate any production bridge (the shim pins
2.7.1 symbols, one op is proven — full op coverage is the real
work, ~200MB native per platform needs a distribution story).
The bridge stays out of scope until they hold.

## Consequences

- The safetensors module owns the container format; the PEFT module
  owns the adapter semantics; neither depends on the other's internals
  beyond the public contracts.
