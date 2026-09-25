# llama.cpp Guide

`cortexj-runtime-llamacpp` plans GGUF models and executes them through llama.cpp.
The JNI execution bridge is intentionally deferred (tracked by the spec's v1.0 scope);
the provider is honest about it at every level.

## What works today

- GGUF artifact discovery, quantization extraction, memory estimation, planning.
- Probe: checks `CORTEXJ_LLAMACPP_LIB` (env) / `cortexj.llamacpp.lib` (system property),
  `java.library.path`, and standard install prefixes for `libllama`.
- Actionable diagnostics: when the native library is missing, `doctor`, `plan`, and
  `run` explain exactly what to do.

## Recommended path: llama-server + remote runtime

```bash
llama-server -m Qwen3-8B-Q4_K_M.gguf --port 8080
export CORTEXJ_REMOTE_BASE_URL=http://localhost:8080
java -jar cortexj-cli-0.1.0-SNAPSHOT.jar run Qwen/Qwen3-8B-GGUF --prompt "hi"
```

The `openai-remote` runtime talks to llama-server's OpenAI-compatible API; planning,
cache, lockfile, and streaming all apply.

## Alternative: provide the native library

```bash
export CORTEXJ_LLAMACPP_LIB=/usr/local/lib/libllama.dylib
```

With a library present the probe reports AVAILABLE; loading then requires the CortexJ
JNI bridge (not part of this build) and fails with `CORTEXJ-NATIVE-6002` and a
remediation pointing at llama-server.

## Planning behavior

- GGUF formats on CPU/GPU; quantization read from the filename when the header is absent.
- Memory estimation uses GGUF header fields (real block counts and sizes) with an
  uncertainty margin (spec §29).
- If GGUF is not viable, the planner reports every rejected candidate with a reason.
