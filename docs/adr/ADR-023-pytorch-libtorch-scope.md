# ADR-023: PyTorch interoperability scope

Date: 2026-10-08. Status: accepted (0.x).

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

Scope-gate answers: problem = checkpoint/adapter round-trip
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

## Production path status (2026-10-10, issue #181)

`cortexj-tensor-libtorch` implements the path: versioned C ABI
(`spike_abi_version`, Java refuses mismatches typed), explicit op
allowlist (add/mul/matmul/relu, F32 CPU), differential tests vs the
CPU reference, live lane green through the real shim + libtorch.
Measured (`-Pperf`, Apple arm64): 128x128 parity (0.7 vs 0.8 ms),
512x512 libtorch 25x faster (2.0 vs 49.4 ms). Remaining before a
1.0 bridge: wider op coverage, per-OS/arch native distribution with
checksums, CI-built shim lane.

## Consequences

- The safetensors module owns the container format; the PEFT module
 owns the adapter semantics; neither depends on the other's internals
 beyond the public contracts.
