# ADR-005: Native binary packaging

Date: 2026-10-25. Status: accepted (ratified retroactively). Spec: v3.0 §§35, 36, 62.

## Context

ONNX Runtime and llama.cpp need native libraries; asking users to
system-install engines destroys the "one dependency" promise, and bundling
natives into the API JAR breaks repository policy and multiplies artefacts.

## Decision

Natives ship **inside their own runtime module** (`runtimes/onnx`,
`runtimes/llamacpp`, `runtimes/isolated`), extracted under the platform cache
at first use. The shared core never contains native code. Loading is
policy-checked (checksum, host OS/arch, native-library policy), and the
`isolated` runtime gives an out-of-process path for engines that must not run
in the application JVM.

## Consequences

- Air-gapped installs work: jars are self-contained, downloads are legacy-free.
- Native crashes cannot take down the Runtime process (isolated path exists).
- Native trust policy failures fail loudly, consistent with ADR-013.
