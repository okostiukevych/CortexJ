# ADR-001: Public API Java baseline

Date: 2026-10-25. Status: accepted (ratified retroactively — documents the
implementation already in `main`). Spec: v3.0 §§7, 12, 13.

## Context

Production JVM teams still run Java 8 in long-lived services; refusing them
locks the Runtime out of the audience that most needs a portable AI layer.
Modern APIs (Flow, records, `List.of`) cannot appear in shared signatures or
Java 8 consumers stop compiling.

## Decision

The shared API tier (`io.cortexj`, model, spi, core, planner) compiles to
**Java 8 bytecode** (`--release 8`, `cortexj.shared.release`), uses Java 8
types only (`CompletionStage` for async, own classes, no records in shared
signatures). Modern consumers get capability through separate modules compiled
on higher baselines (`platform/java11`, `streaming/flow`, `platform/java17+`),
which never leak into shared signatures.

## Consequences

- Java 8 consumers see zero bytecode or type breakage across Runtime lines.
- Modern-container features enter only through LTS-scoped modules (§22).
- Cross-module API review is enforced by the JPMS profile (`-Pjpms`) and
  japicmp once the baseline release exists (see §74).
- Evidence: `cortexj.shared.release=8` in the parent POM;
  `streaming/flow`, `platform/java11*…` modules gated by JDK profiles.
