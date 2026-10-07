# API Stability (spec 73/74)

CortexJ follows semantic versioning. The contract surface is split into four tiers,
with the boundary between them enforced by JPMS module boundaries (spec 95) and the
`jpms` build profile.

## Stability tiers

| Tier | Packages | Stability guarantee |
|---|---|---|
| **Public API** | `io.cortexj`, `io.cortexj.model`, `io.cortexj.runtime`, `io.cortexj.planner`, `io.cortexj.hardware`, `io.cortexj.stream`, `io.cortexj.diagnostics`, `io.cortexj.error` | Broken only in major releases; Java 8 bytecode baseline (§13); no records in shared signatures (§13.3) |
| **Stable SPI** | `io.cortexj.spi.runtime`, `io.cortexj.spi.repository`, `io.cortexj.spi.policy`, `io.cortexj.spi.platform`, `io.cortexj.spi.artifact` | Additive evolution; existing provider contracts keep compiling (§957 sections, TCK-verified) |
| **Experimental SPI** | `io.cortexj.spi.conversion` | May evolve during 1.0.x; converter `evaluate()` is format-based by design |
| **Internal** | `io.cortexj.*.internal`, `io.cortexj.core.*` internals, everything not exported in module-info | No guarantee whatsoever; not on the module path exports |

Experimental packages are explicitly marked: `io.cortexj.spi.conversion` is the only
tier-3 surface and is called out in this document; it will be promoted to Stable SPI
with the first production release that ships its converter set.

## Java 8/11 consumer protection (§74)

- Shared modules compile to Java 8 bytecode (`--release 8`), verified in CI lanes on
  real JDK 8 (ubuntu/macos/windows).
- The public API uses only Java 8 types: `CompletionStage` for async (§13.1), the own
  `TokenStream` contract for streaming (§13.2), classes instead of records (§13.3).
- `Flow.Publisher` / `Flux` adapters live in Java 11+/17+ modules
  (`cortexj-streaming-flow`, `cortexj-spring-ai`, `cortexj-langchain4j`) and never
  leak into shared signatures.

## Binary compatibility checks in CI

The `compat` Maven profile runs `japicmp` binary-compatibility checks against a
published baseline once a non-snapshot release exists:

```bash
mvn -Pcompat verify -Dcortexj.compat.skip=false -Dcortexj.api.baseline=1.0.0
```

The profile skips cleanly by default (`cortexj.compat.skip=true`): there is no
released baseline to compare against yet. After 1.0.0 ships, CI will fail on any
binary-breaking change to the Public API tier in minor/patch releases.

> **Known gap (audit 2026-10-07, #148):** the profile compares `cortexj-api`
> only - breaking `cortexj-spi`/`cortexj-model`/`cortexj-planner` is not caught
> automatically. Until the scope is extended (tracked for the 1.0 enforcement
> date), SPI changes require manual review against the TCK. Related
> asymmetry: `Models.loadAsync` returns `Future`, not the `CompletionStage`
> baseline of §13.1 - kept for Java 8 ergonomics, documented here instead of
> changed.

## What may change

- Internal packages and implementation details (module-info exports keep them
  encapsulated).
- Experimental SPI until promoted.
- Anything behind JDK profiles (platform modules) as long as the shared contract and
  the `PlatformModule` SPI stay stable.
