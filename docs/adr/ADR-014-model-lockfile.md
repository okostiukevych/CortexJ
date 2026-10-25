# ADR-014: Model lockfile

Date: 2026-10-25. Status: accepted (ratified retroactively). Spec: v3.0 §§63, 30.

## Context

"Works on my machine" is unacceptable when models are multi-GB, multi-variant
artifacts that update in place. A deployment must be reconstructible exactly.

## Decision

A **lockfile artifact** binds a model reference to pinned revisions, artifact
identities (ADR-007), requirements and optional bundle hashes. It is a
first-class input to plan/deploy: planning against a lockfile is deterministic,
and divergence from the lock explains itself. Bundle support lets a lockfile
describe a fully offline payload (with ADR-008 prefill semantics).

## Consequences

- Reproducible deployments; provenance manifests embed lockfile references.
- Lockfile drift is diagnosable (support report shows expected vs actual).
- Lockfile format changes require an ADR (explicitly versioned).
