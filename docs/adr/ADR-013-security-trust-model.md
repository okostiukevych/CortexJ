# ADR-013: Security/trust model

Date: 2026-10-25. Status: accepted (ratified retroactively). Spec: v3.0 §§35, 64, 65.

## Context

An inference platform downloads models and loads natives by design; treating
all inputs as trusted makes every AI dependency a code-execution vector. The
research track (§ADR-028 later) legitimately runs user code, which must not
dilute Runtime defaults.

## Decision

The Runtime applies explicit policy layers: **repository trust policy**
(allow/deny per repository), **artifact integrity** (checksums; signature
verification where configured), **native library policy** (checksum + host
match before load), and **network egress policy**. The Runtime executes no
arbitrary downloaded code. Policy decisions are explainable in support reports
and fail loudly by default; isolating untrusted execution is the `isolated`
runtime path's job. `SECURITY.md` states supported versions and reporting.

## Consequences

- Default posture denies the unknown; enabling more is an explicit, auditable
  configuration act.
- Research isolation is a separate, stated trust profile (not a Runtime
  regression).
- Trust failures never produce silent behaviour (matches ADR-004).
