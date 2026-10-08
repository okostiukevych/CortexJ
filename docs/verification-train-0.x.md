# Train 0.x verification

Date: 2026-10-08. `mvn clean verify` BUILD SUCCESS, 55 modules. New:
`train-api` (7 tests), `train-peft` (8), `train-djl` (2), `train-remote` (3).

- Orchestration: honest per-backend reports, first-FULL selection,
  explicit `requireBackend` respected (never silently ignored), typed
  `PLAN_NO_CANDIDATE` when nothing supports the run.
- LoRA: config validation, merge math hand-computed
  (`W' = W + (alpha/rank)·B·A`, base untouched), shape-mismatch guard,
  safetensors round-trip, canonical-header content check.
- Interop: foreign adapter naming (HuggingFace PEFT style) fails typed;
  corrupted containers and lying offsets fail typed.
- Backends honest on this machine: DJL reports unsupported (only
  OnnxRuntime engines installed, no training engine); remote trainer
  unconfigured → unsupported, configured → partial.

## Explicitly NOT verified here (tracked, not silent)

- Live LoRA fine-tuning through a real external engine and deployment
  of the produced adapter through Runtime — needs a training engine
  (DJL PyTorch) or a live fine-tuning endpoint; tracked as a follow-up
  issue.
- Two FULL backends on one machine (DoD item p.6) — the 0.x providers
  are deliberately probe-gated partial/unavailable; synthetic FULL
  backends cover the orchestration contract in unit tests.
