# ADR-008: Cache layout

Date: 2026-10-25. Status: accepted (ratified retroactively).

## Context

The cache must survive restarts, parallel JVMs and interrupted downloads;
leaking partial artifacts into the model path breaks trust. Layout is
implementation but its guarantees are a contract.

## Decision

Cache lives in the `cache` module under the platform cache directory with a
**versioned layout**, atomic staging (partial downloads never visible as
complete artifacts), integrity verification before reuse, and eviction rules
that preserve lockfile-pinned artifacts. Cache state is inspectable (doctor
output) so "why did it not reuse" is answerable.

## Consequences

- Interrupted downloads restart cleanly (no corrupt-hit path).
- Air-gapped seeding works: prefill the layout, run offline.
- Layout changes require an ADR; the version flag exists for that.
