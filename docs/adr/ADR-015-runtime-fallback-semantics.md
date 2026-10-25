# ADR-015: Runtime fallback semantics

Date: 2026-10-25. Status: accepted (ratified retroactively). Spec: v3.0 §§39, 40.

## Context

Engines crash, GPUs vanish, OOM hits. Automatic engine switching can silently
degrade output quality — exactly what the platform exists to make visible.
Yet refusing all fallback makes the product brittle in production.

## Decision

**No silent fallback.** Plans carry an ordered fallback list built from the
same hard/soft tiers as the selection (ADR-004); at execution time the runtime
layer degrades along that **declared** path with probe-health scoring
(`ProbeHealthScoringTest`), and the platform's health/circuit logic records and
surfaces every degradation as plan/support evidence. Users can disable
fallbacks per request; the strictest setting fails fast.

## Consequences

- "Why did quality change?" is always answerable from plan + events.
- Health scoring applies to runtime selection, not just re-selection.
- Tests pin both behaviours: fallback happens (declared) and never happens
  silently (strict mode).
