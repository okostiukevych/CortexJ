# ADR-010: ONNX provider architecture

Date: 2026-10-25. Status: accepted (ratified retroactively).

## Context

ONNX Runtime is the most broadly available engine with real CPU/GPU coverage;
embedding its session lifecycle in core couples the whole platform to one
vendor's API cadence.

## Decision

ONNX support lives in `runtimes/onnx` (onnxruntime-java, own native packaging
per ADR-005), implemented as a descriptor-driven `RuntimeProvider` (formats
ONNX, device CPU/GPU). Core only ever sees the descriptor, support matrix and
probe result. ONNX remains a hard requirement for the MVP runtime set; CUDA
lanes are opt-in CI on self-hosted runners, never a gate for the core paths.

## Consequences

- Engine version bumps don't touch shared contracts.
- GPU verification is explicit and self-hosted (never silently skipped).
- Descriptor honesty (supported formats/devices) is TCK-checked.
