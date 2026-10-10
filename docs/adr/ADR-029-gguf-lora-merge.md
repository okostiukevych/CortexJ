# ADR-029: GGUF-side LoRA merge design

Date: 2026-10-10. Status: accepted (0.x).

## Context

Train produces standard safetensors LoRA adapters; Runtime executes
GGUF checkpoints. The loop closes by merging the adapter into the
quantized model. Dequantization is lossy by nature — the platform
must say so, and unsupported combinations must fail typed.

## Decision

- Offline merge first (`cortexj-convert-lora`): read base GGUF
  tensors, dequantize, apply `W + (alpha/rank)·B·A`, requantize,
  write a new GGUF plus the conversion manifest (base revision,
  adapter checksum, converter id). Runtime composition stays a
  follow-up.
- Dequant coverage 0.x: F32/F16/BF16, Q8_0, Q4_0, Q4_1, Q5_0, Q5_1
  (unambiguous block layouts, hand-verified). K-quants and anything
  else fail typed with the type id — no guessed layouts, ever.
- Requant policy (`--quant`): `same` (default, round-trip to the base
  tensor's quant where supported), `f32`, `f16`, `q8_0`, `q4_0`.
  K-quant output fails typed — no silent precision claims.
- Tensor mapping is explicit: the request carries (ggufTensorName,
  adapterModule) pairs. A llama-family convention helper
  (`blk.N.attn_q.weight` ↔ `q_proj`, etc.) is convenience only and
  never guesses silently: unmapped tensors pass through untouched,
  unmapped adapters fail typed.
- Original artifacts are immutable; the merged file is separate.

## Consequences

- Merged GGUF loads through the standard Runtime path.
- Quality delta is measured (perplexity spot-check), never asserted.
