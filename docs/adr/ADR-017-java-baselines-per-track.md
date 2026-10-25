# ADR-017: Java baselines per product track

Date: 2026-10-08. Status: accepted.

## Context

One platform-wide Java 8 rule would freeze research on 2014-era APIs;
one modern-only rule would lock out the legacy production apps that
justify the Runtime (G-005). Compatibility is therefore per product,
not per platform.

## Decision

| Track | Minimum | Recommended | Enforced by |
|---|---|---|---|
| Runtime API/Core/SPI + shared contracts | Java 8 | 21/25 | `maven.compiler.release=8` (default) |
| Starters/platform per LTS | matching LTS | matching LTS | profile modules + starter JVM check |
| Lab / Data / Experiments | Java 17 | 21+ | `release=17` in module pom |
| Train | Java 17 | 21/25 optimized tier | `release=17` in module pom |
| Tensor / Autograd / Research | Java 21 | 25 (FFM/vector) | `release=21` in module pom |
| `runtimes/jinfer` (precedent) | Java 25 | 25 | `release=25`, `jdk25plus` profile only |

Rules:

1. Shared public signatures stay Java 8-loadable: no records, sealed
 classes, `Flow`, virtual threads or FFM in `api/spi/model` and the
 new shared contracts. Modern features live in LTS/track modules
 behind SPI (AP-010, AC-JAVA-005).
2. Capability-first internals: version checks are distribution
 boundaries, never planner logic.
3. Starter/JVM mismatch fails fast with an actionable diagnostic
 (AC-JAVA-004), never a `NoSuchMethodError`.

## Consequences

- CI matrix grows per track baseline; a module built for 17+
 never runs on the Java 8 lane.
- Tensor/Autograd/Research may use records/sealed/FFM freely inside
 their modules once their own ADR lands.
