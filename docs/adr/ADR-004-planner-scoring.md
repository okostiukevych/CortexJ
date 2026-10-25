# ADR-004: Execution Planner scoring

Date: 2026-10-25. Status: accepted (ratified retroactively). Spec: v3.0 §§23–27, 29.

## Context

Runtime choice affects correctness (a GGUF-only model served by an ONNX engine
is a load failure) and cost (device fit, memory, download size). Scoring that
blends preference with feasibility produces unexplainable picks and silent
failures.

## Decision

Two-tier scoring:

1. **Hard constraints** — capability, artifact compatibility, Java/device
   floors, probe health and policy gate: a candidate that fails them is
   rejected with a coded `PlanReason`, never silently preferred.
2. **Soft scoring** — device preference, memory pressure, download size,
   quantisation policy, benchmark hints (opt-in): ranks surviving candidates.

The result is an **explainable plan** (`ExecutionPlan.explanation()`) carrying
selection reasons, warnings, fallbacks, memory estimate, downloads and native
requirements. Fail-before-expensive-execution is the standing rule (unsupported
combinations error out up front).

## Consequences

- Users can see *why* a runtime was chosen (AC-PLANNER-* criteria depend on it).
- The plan is data: testable without engines (`valid-plan rate` harness,
  compatibility matrix KPI §124.3).
- New scoring rules plug in as additive rules; the two-tier order is fixed.
