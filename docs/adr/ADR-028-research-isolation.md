# ADR-028: Research-code isolation model

Date: 2026-10-08. Status: accepted. Spec: v3.0 §149.

## Context

Research necessarily executes user-authored model/training code,
while Runtime repository loading must never execute arbitrary
downloaded code by default. Two trust profiles must not blur.

## Decision

- Research modules (`research/*`, notebook kernels, training workers)
  run under a distinct trust profile: user-authored code is expected
  and executed locally.
- Runtime defaults stay untouched: no arbitrary repository code
  execution, signed/checksummed artifacts where configured, repository
  trust policy, native library policy, network egress policy.
- Notebook kernels and training workers SHOULD support
  process/container isolation profiles for untrusted experiments;
  process isolation is demonstrated by the existing isolated-worker
  runtime, container profiles are future work.
- Cross-track artifacts keep the versioned provenance schema; the
  trust profile of the producer is metadata, not a security boundary.

## Consequences

- A research module never weakens a Runtime default; the guard test
  fails on any dependency from Runtime into research.
- Untrusted experiments require opting into an isolation profile
  explicitly.
