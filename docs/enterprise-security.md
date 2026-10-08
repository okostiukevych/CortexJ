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
always does; Hugging Face marks community models). Since audit 2026-10-07
(#143) a missing note additionally requires an artifact checksum under
`TRUSTED_ONLY` — unchecked local files no longer bypass the policy.

> Spec v3.0 §125.10/§149 (issue #161): the strict posture above is the
> **Runtime production boundary** and is never relaxed for Research
> ergonomics. Future research tracks execute user code under a separate
> trust profile with process/container isolation; any proposal weakening
> a Runtime control requires an ADR and is rejected by default.

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

## Enterprise runtime policy (§128)

An organization can enforce a centrally supplied runtime policy without writing code:
drop `enterprise-policy.properties` into the platform working directory
(`<CORTEXJ_HOME>`, by default `~/.cortexj`) — or point
`-Dcortexj.enterprise.policy=<path>` at a file elsewhere. Every plan then applies
the policy; rejected candidates appear in the plan explanation with a clear reason.

Supported keys (all optional; absent = unrestricted):

```properties
# comma-separated runtime ids that must never be used
forbiddenRuntimes=llamacpp,djl

# false = cache/local-only operation: candidates requiring a download are rejected
allowDownloads=false

# comma-separated license whitelist; artifacts without an approved license are rejected
approvedLicenses=apache-2.0,mit

# true = artifacts without a declared checksum cannot be planned
requireVerifiedArtifacts=true

# false = non-CPU (GPU/NPU) device candidates are rejected
allowGpu=false

# false = remote runtimes are rejected (default remote set: openai-remote)
allowRemote=false
remoteRuntimes=vendor-cloud,openai-remote
```

Behavior notes:

- The file is applied automatically at platform construction; the effective policy
  file path is logged at INFO.
- An unreadable file is NOT applied and logs a WARNING (fail-open by design: an ops
  typo must not silently disable the platform) — check the logs after rollout.
- A file that declares no restrictions is ignored with a WARNING (no silent no-ops).
- The policy composes with user-supplied `ExecutionPolicy` instances from the
  builder; all of them must allow a candidate.
