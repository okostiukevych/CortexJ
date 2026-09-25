# llama.cpp Guide

`cortexj-runtime-llamacpp` plans GGUF models and executes them locally through a
**managed llama-server process**: the provider starts `llama-server` with the resolved
artifact, waits for its health endpoint, then serves generation and token streaming
through its OpenAI-compatible API. The process is shut down with the model handle.

## What works

- GGUF discovery, quantization extraction, memory estimation, planning.
- Probe: finds `llama-server` via `CORTEXJ_LLAMACPP_SERVER` (env) /
  `cortexj.llamacpp.server` (system property) or standard install prefixes, and also
  checks `CORTEXJ_LLAMACPP_LIB` for the (future) JNI bridge.
- **Execution**: blocking generation and SSE token streaming with usage parsing,
  process lifecycle management (health polling, log draining, graceful kill).
- Actionable diagnostics at every level.

## Run a local GGUF model

```bash
# point the provider at your llama-server binary
export CORTEXJ_LLAMACPP_SERVER=/opt/homebrew/bin/llama-server
java -jar cortexj-cli-0.1.0-SNAPSHOT.jar run ./Qwen3-8B-Q4_K_M.gguf --prompt "hi"
```

The platform starts llama-server on a free local port with the planned artifact,
streams tokens back, and stops the process when the handle closes.

## Manual llama-server also works

```bash
llama-server -m Qwen3-8B-Q4_K_M.gguf --port 8080
export CORTEXJ_REMOTE_BASE_URL=http://localhost:8080
java -jar cortexj-cli-0.1.0-SNAPSHOT.jar run Qwen/Qwen3-8B-GGUF --prompt "hi"
```

## Quantization conversion

Use the bundled deterministic converter to shrink float32 GGUF files before running:

```bash
java -jar cortexj-cli-0.1.0-SNAPSHOT.jar convert ./model.gguf --to f16 --out ./converted
```

## GraalVM/native-image compatibility (§96)

The llama-server bridge is pure Java (process + HTTP, no JNI) — the most
native-image-friendly execution path of the platform. The llama-server binary itself
must be present at native-image runtime; verify with the probe before claiming
support.

## Planning behavior

- GGUF formats on CPU/GPU; quantization read from the filename when the header is absent.
- Memory estimation uses GGUF header fields (real block counts and sizes) with an
  uncertainty margin (spec §29).
- If GGUF is not viable, the planner reports every rejected candidate with a reason.
