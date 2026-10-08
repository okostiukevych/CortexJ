# ADR-018: Notebook/kernel integration strategy

Date: 2026-10-08. Status: accepted (0.x slice). Spec: v3.0 §§136, 151.

## Context

Lab needs interactive Java execution with rich display. Writing a
Jupyter wire-protocol kernel from scratch is months of work and
directly contradicts adapter-first (§125.5, §159).

## Decision

Two-step, in this order:

1. **0.x: `LabSession` over `jdk.jshell.JShell`** (JDK-built-in, zero
   new dependencies). Covers eval, display contracts, environment
   descriptor and project-skeleton export. Proves the API shape real
   kernels will implement.
2. **Follow-up: adapter over a mature Java/JShell Jupyter kernel**
   (IJava-style). Own protocol implementation only if a spike proves
   integration limitations.

Scope-gate §159 answers: problem = JVM-first interactive AI without a
Python kernel; existing libs insufficient alone only at the CortexJ
display/provenance layer (plain JShell has no plan/metric rendering);
minimal solution = this slice; acceptance = DoD Lab §154 items 1–9
except kernel-dependent item re-scoped to JShell; Runtime impact =
none (new modules only); native cost = none.

## Consequences

- `lab-api` exposes `LabSession` as an interface so the future kernel
  adapter is a new implementation, not a rewrite.
- Jupyter-specific work is tracked, not silently dropped.
