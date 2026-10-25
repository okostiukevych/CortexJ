# CortexJ ADRs

Architecture Decision Records are part of the public product contract
(spec §101/§119). New ADRs follow the house style of ADR-016..029
(context / decision / consequences), cite the spec section they serve,
and carry an explicit status.

## Required-before-coding set (spec §119)

| Spec | ADR | Title |
|---|---|---|
| §119.1 | [ADR-001](ADR-001-public-api-java-baseline.md) | Public API Java baseline |
| §119.2 | [ADR-002](ADR-002-lts-starter-strategy.md) | LTS starter strategy |
| §119.3 | [ADR-003](ADR-003-runtimeprovider-spi.md) | RuntimeProvider SPI |
| §119.4 | [ADR-004](ADR-004-planner-scoring.md) | Execution Planner scoring |
| §119.5 | [ADR-005](ADR-005-native-binary-packaging.md) | Native binary packaging |
| §119.6 | [ADR-006](ADR-006-streaming-abstraction.md) | Streaming abstraction |
| §119.7 | [ADR-007](ADR-007-model-artifact-identity.md) | Model artifact identity |
| §119.8 | [ADR-008](ADR-008-cache-layout.md) | Cache layout |
| §119.9 | [ADR-009](ADR-009-hugging-face-resolution.md) | Hugging Face resolution |
| §119.10 | [ADR-010](ADR-010-onnx-provider-architecture.md) | ONNX provider architecture |
| §119.11 | [ADR-011](ADR-011-llamacpp-binding-approach.md) | llama.cpp binding approach |
| §119.12 | [ADR-012](ADR-012-framework-integration-boundary.md) | Framework integration boundary |
| §119.13 | [ADR-013](ADR-013-security-trust-model.md) | Security/trust model |
| §119.14 | [ADR-014](ADR-014-model-lockfile.md) | Model lockfile |
| §119.15 | [ADR-015](ADR-015-runtime-fallback-semantics.md) | Runtime fallback semantics |

ADR-001..015 were ratified retroactively (2026-10-25) from the implemented
state: each record cites the modules, TCK contracts and tests that pin the
decision.

## v3.0 track set (spec §151)

| ADR | Title |
|---|---|
| [ADR-016](ADR-016-track-boundaries.md) | Product-track dependency boundaries |
| [ADR-017](ADR-017-java-baselines-per-track.md) | Java baselines per product track |
| [ADR-018](ADR-018-notebook-kernel-strategy.md) | Notebook/kernel integration strategy |
| [ADR-019](ADR-019-dataset-abstraction-vs-adapters.md) | DataFrame/Dataset abstraction vs adapters |
| [ADR-020](ADR-020-provenance-schema.md) | Provenance schema |
| [ADR-021](ADR-021-training-backend-spi.md) | Training backend SPI |
| [ADR-022](ADR-022-peft-artifact-model.md) | PEFT artifact model |
| [ADR-023](ADR-023-pytorch-libtorch-scope.md) | PyTorch/LibTorch interoperability scope |
| [ADR-024](ADR-024-tensor-memory-ownership.md) | Tensor memory ownership model |
| [ADR-025](ADR-025-tensor-backend-spi.md) | Tensor backend SPI |
| [ADR-026](ADR-026-autograd-graph-model.md) | Autograd graph model |
| [ADR-027](ADR-027-distributed-collective.md) | Distributed collective abstraction |
| [ADR-028](ADR-028-research-isolation.md) | Research-code isolation model |
| [ADR-029](ADR-029-gguf-lora-merge.md) | GGUF LoRA merge |
