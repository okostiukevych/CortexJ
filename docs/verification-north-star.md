# North-star demo verification + release train

Date: 2026-10-08. `mvn clean verify` BUILD SUCCESS, 61 modules.

## End-to-end reference flow (ADR-028, CPU-only)

`cortexj-example-north-star` runs the deterministic chain green:
CSV dataset → typed frame → fingerprint → batches/split →
reproducible experiment (embedded provenance: revision, dataset hash,
seeds) → LoRA adapter (merge math, base untouched) → safetensors
container round-trip → provenance manifest → model lockfile.

## GPU path (Metal, Apple Silicon, 2026-10-10)

- The llama.cpp nightly ships the Metal backend (`libggml-metal`);
  with `--n-gpu-layers 99` all layers assign to `MTL0 (Apple M3 Pro)`
  (verbatim server log).
- The provider exposes it honestly: `runtimeOptions.gpuLayers` maps to
  `--n-gpu-layers` (typed rejection of anything else); the option is
  part of the shared-endpoint cache key (CPU and GPU servers never mix).
- Gated live contract (`RealLlamaServerGatedTest.gpuOffloadRoundTrip`
  + `ProcessLlamaServerLauncherTest.gpuLayersOptionParsesHonestly`).

## Release train (per-track maturity, capability matrix per release)

| Track | Version | Maturity | Notes |
|---|---|---|---|
| Runtime | 0.1.0 | beta (1.0 DoD recorded) | P0; onnx/llamacpp/remote/jinfer/djl/isolated providers |
| Lab | 0.x | incubating (DoD recorded) | JShell session, display, export; Jupyter kernel adapter follow-up |
| Data | 0.x | incubating | Dataset/Frame + CSV/Parquet; Arrow/JDBC/Tablesaw/Tribuo adapters follow |
| Experiments | 0.x | incubating | lifecycle + local store; external trackers follow |
| Train | 0.x | incubating (DoD recorded) | full policy surface + LoRA artifacts + safetensors; live fine-tune follow-up (#176) |
| Tensor | 0.x | incubating (DoD recorded) | CPU reference + vector backend; conformance suite green (incl. gather/scatter) |
| Autograd | 0.x | incubating (DoD recorded) | tape graph + differential suite + hooks/checkpointing |
| Distributed | Phase A + B | incubating | single-node + multi-node data-parallel over ZeroMQ; C needs ADR |
| Research | 0.x | incubating | cards/citations/bundles; benchmark suites follow |

Messaging stays two-level: Runtime is the production product; no
PyTorch-replacement or "any HF model works" claims.

## Explicitly NOT verified here (tracked, not silent)

- Runtime-deploy step of the demo with a real model artifact
  (live-verified by the provider tests; the demo chain itself is
  model-free).
- Live LoRA fine-tune through a real engine (#176).
- Container isolation profiles for untrusted experiments (ADR-028:
  future work).
