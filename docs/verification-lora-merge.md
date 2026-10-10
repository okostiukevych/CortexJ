# GGUF-side LoRA merge verification (ADR-029)

Date: 2026-10-10. Module: `cortexj-convert-lora` (11 unit tests:
dequant hand-computed per block layout, quant round-trip bounds,
merge math, manifest, typed failures).

## Live verification (LFM2.5-350M-Q8_0, 379MB, deleted after per policy)

- 148 tensors (55 F32 + 93 Q8_0); base dequantizes with zero NaNs
  and sane magnitudes (embeddings max ~0.31).
- Zero-adapter merge of 6 tensors, SAME policy: fidelity
  max-zero-merge-error 0.0077 (one Q8_0 step); the merged file
  loads through the CortexJ JInfer provider and generates
  (`capabilities=[TEXT_GENERATION]`, greedy "Paris" like the base).
- Pure copy-through generates identically (writer path valid);
  single- and 3-tensor merges generate identically (bisection
  clean).

## Findings fixed on the way (both real)

1. The reader hardcoded 64-byte tensor-data padding; real llama.cpp
   files use 32 (spec default). Every tensor read shifted by 32
   bytes (millions of NaNs). The reader now honors
   `general.alignment` (default 32); the writer declares the 64 it
   pads, so our files stay self-describing and byte-compatible.
2. An undeclared-alignment merged file loads but generates empty:
   writer/reader must agree through the declaration, never by
   coincidence. Covered by the manifest + declaration above.

## Planner integration

None needed: the merged file is a regular GGUF artifact with a
conversion manifest (base revision, adapter checksums, converter
id). The planner sees it like any GGUF; adapter presence is recorded
in the manifest, not in planning inputs.

## Explicitly NOT in 0.x (typed failures, ADR-029)

K-quant dequant/requant, K-quant output policy, runtime composition
(adapter applied per forward), perplexity spot-check on a corpus.
