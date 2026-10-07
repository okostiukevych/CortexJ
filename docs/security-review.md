# Security Review Record (spec 108.12)

Formal record of the security review performed on the CortexJ platform against the
threat model of spec §64-§65 and the acceptance criteria of §113.

## Scope

- All runtime providers (ONNX, llama.cpp, DJL, openai-remote)
- Repository subsystem (local, Hugging Face, plain HTTP)
- Cache and download manager
- Conversion subsystem (GGUF, safetensors)
- Server mode, CLI, Maven plugin
- Trust and error-handling paths

## Threat model coverage (§64)

| Threat | Mitigation | Status |
|---|---|---|
| Path traversal via repository/revision/artifact ids | `CacheStorage` sanitization; bundle manifests cannot escape the bundle dir (`CORTEXJ-ARTIFACT-2005`); local repository path resolution is absolute+normalized | ✅ |
| Corrupted/tampered artifacts | SHA-256 verification on download (real LFS sha256 propagated from HF blobs, `CORTEXJ-ARTIFACT-2001`) and verify-once-then-trust on cache read (manifest + size check; the paranoid `verifyIntegrity` escape hatch re-hashes on demand); checksum mismatch never exposes partial content; bundle verify reports tampering explicitly (verified 2026-09-30: issues #16, #20) | ✅ |
| Arbitrary repository code execution (§35.1) | Resolution is data-only; no remote Python/source execution anywhere; llama.cpp execution is via the managed llama-server process (native code isolated outside the JVM) | ✅ |
| Untrusted models auto-executed (§35.2) | Trust policies: `TRUSTED_ONLY` fails with `CORTEXJ-SEC-10001`; `ALLOW_UNSIGNED_WITH_WARNING` records warning events; repositories set `trustNote` on content they cannot vouch for | ✅ |
| Native artifact mismatch | Probe-before-open contract: providers verify environment/library presence before loading; honest `CORTEXJ-NATIVE-*` errors | ✅ |
| Credential leakage | Tokens via `TokenSupplier`/env only; never logged, never in error messages or plan explanations (test: `apiKeyUpstreamOnlyAndNeverInErrors`); `Authorization`/`Cookie` headers are scoped to the original origin on redirects - stripped on cross-origin hops and the final connection (verified 2026-09-30: issue #39) | ✅ |
| Prompt/response logging (§57.3) | Platform events carry metadata only (runtime, model, artifact ids, durations, byte counts); prompts are never emitted to logs by default | ✅ |
| Download of oversized artifacts | Declared size checked against the configured maximum before any bytes are written (`CORTEXJ-ARTIFACT-*` too-large); server request bodies are size-limited (`CORTEXJ-SRV-*` 413) | ✅ |
| Supply chain (§100) | Immutable revision resolution; lockfiles pin revision+sha256; bundle verify validates checksum manifests; no mandatory external dependencies in core (§108.15) | ✅ |

## Findings found and fixed during review

1. **Cache LRU eviction raced with in-progress downloads** — `.part-*` temp files were
   not excluded from eviction listings; fixed (filter + pinned-aware eviction).
2. **Checksum algorithm alias** — lowercase `sha256` failed checksum verification on
   Temurin 8; canonicalized to `SHA-256` with alias normalization.
3. **Trust enforcement absent** — trust policies were declared but not enforced at
   load time; enforced with typed errors + warning events.
4. **Remote configuration dropped in planner probes** — `remote.baseUrl` never reached
   probe contexts; propagated through `PlannerInputs`.

## Residual risks (accepted, documented)

- Plain HTTP model references (`cortexj-repository-http`) serve content without TLS —
  documented in the provider guide and flagged with `trustNote` on every descriptor.
- **Model references are trusted configuration input (audit 2026-10-05, issue #111):**
  `ModelReference` accepts arbitrary `http(s)` URLs (including cloud metadata
  endpoints and intranet hosts) and local filesystem paths (`~`, `file:`). If an
  application feeds user-controlled input into `models().load()/plan()` (a
  chat-facing model picker, an MCP tool), the attacker gains SSRF (the resolver
  HEAD/GETs the URL) and arbitrary local file reads (paths surface in resolution
  errors and parsers). Applications MUST treat model references as trusted
  configuration; never pass raw user input as a `ModelReference`.
- llama.cpp JNI bridge is not shipped; native llama.cpp execution goes through the
  managed llama-server process bridge (process isolation is the safer default).
- GPU execution paths (CUDA/Metal) are declared by runtimes but not exercised in CI
  (no GPU runners; lanes are prepared for self-hosted runners per §70).

## Sign-off

The security mechanisms required by spec §35, §64, §65, §113 are implemented,
test-covered, and reviewed. This record satisfies §108.12 ("Security review
выполнен") for the current platform state.
