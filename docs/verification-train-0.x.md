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

## Live verification (2026-10-09, follow-up closed)

- Live LoRA through a real engine: the DJL PyTorch engine trains a
  tiny linear model (`y = 2x` learned: `y(10) = 20.01`), the trained
  weight delta becomes the LoRA adapter artifact (safetensors,
  checksummed, reads back through the standard PEFT path), the backend
  reports FULL for the verified path. Contract test in
  `DjlLiveTrainingTest` skips loudly without the engine.
- Adapter deployability: the artifact loads through the PEFT module;
  Runtime-side direct LoRA application to GGUF checkpoints stays a
  declared follow-up (no fake compatibility).

## Explicitly NOT verified here (tracked, not silent)

- Two FULL backends on one machine (DoD item p.6) — the remote trainer
  stays deliberately probe-gated partial; synthetic FULL backends cover
  the orchestration contract in unit tests.
