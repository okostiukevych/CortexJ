# ADR-007: Model artifact identity

Date: 2026-10-25. Status: accepted (ratified retroactively).

## Context

Reproducibility demands the same model today and in six months. Location
(URLs, mirrors, cache paths) is not identity; quantised variants of one model
are different artifacts that must not be conflated.

## Decision

Artifacts are addressed by **immutable revision + content fingerprint**
(`ModelArtifact` identity, `Fingerprint` in the Data track; content-derived by
the cross-adapter contract). Resolution maps a `ModelReference` to
`ResolvedModel(s)` with pinned revisions; the lockfile (ADR-014) then binds
deployments to those fingerprints. Identity is independent of repository URL
and cache layout.

## Consequences

- Two hubs serving the same content resolve to the same fingerprint.
- Lockfiles and provenance manifests reference identity, not locations.
- Fingerprint determinism is a CI-checked contract, not a doc promise.
