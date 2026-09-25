# Enterprise Security

CortexJ implements the security posture required by spec §35, §64, §65, §113.

## Trust policies (§35.2)

```java
CortexJ platform = CortexJ.builder()
        .trustPolicy(TrustPolicy.TRUSTED_ONLY)
        .build();
```

- `TRUSTED_ONLY` — loading a model whose descriptor carries a `trustNote` (e.g. plain
  HTTP served, community upload, unverified checksums) fails with `CORTEXJ-SEC-10001`
  and remediation.
- `ALLOW_UNSIGNED_WITH_WARNING` (default) — the model loads and a WARNING platform
  event is recorded.
- `CUSTOM` — decisions delegated to your execution policies (see
  [writing planner policies](writing-planner-policy.md)).

Repositories set `trustNote` on descriptors they cannot vouch for (the HTTP repository
always does; Hugging Face marks community models).

## Artifact limits and path safety (§35.3, §113)

- Declared size is checked before download; oversized artifacts fail before any bytes
  hit the disk.
- Cache keys are sanitized — `../` traversal attempts in repository/revision/artifact
  ids fail with `CORTEXJ-ARTIFACT-2005`.
- Bundle manifests cannot reference files outside the bundle directory.
- Checksums are verified on download; mismatches fail with
  `CORTEXJ-ARTIFACT-2001` and the partial file is never exposed.

## Credential handling (§112, §113)

- Tokens are supplied via `TokenSupplier` (`CortexJBuilder.tokenSupplier(...)`,
  `CORTEXJ_REMOTE_API_KEY`, `HF_TOKEN`/`HUGGING_FACE_HUB_TOKEN` environment variables).
- Tokens are never logged and never appear in error messages or plan explanations.
- Prompts and responses are not logged by default (spec §57.3); platform events carry
  only metadata (runtime, model, artifact, durations, byte counts).

## Supply chain (§100)

- No repository code is ever executed (§35.1) — resolution is data-only.
- Every artifact is addressed by immutable revision; mutable branches are pinned at
  resolution time.
- Lockfiles and bundles pin exact revisions and sha256 checksums for later verification.

## Native runtimes (§65)

- Native libraries are probed, not assumed: probe failures are honest and actionable.
- llama.cpp is optional; the recommended path (`llama-server` + `openai-remote`)
  isolates native code outside the JVM entirely.
