# ADR-002: LTS starter strategy

Date: 2026-10-25. Status: accepted (ratified retroactively).

## Context

"One JAR that runs everywhere" would force the lowest common denominator at
every layer and complicate native packaging; separate artifact sets would make
dependency choice a research project.

## Decision

One **starter artifact per supported LTS** — `cortexj-starter-java8`,
`-java11`, `-java17`, `-java21`, `-java25` — each aggregating the same shared
core with the LTS-appropriate platform module. **Business code does not change
between starters**: the public API surface is identical; only the dependency
coordinate differs. Each starter is verified in the CI matrix on its own JDK
lane.

## Consequences

- Dependency choice is one line in `pom.xml` (starter coordinate), documented
 per LTS.
- Java 21/25 examples (the version-examples contract) compile and run with only the starter
 changed (`examples/java21`, `examples/java25-jinfer`).
- Starter divergence would break AC-JAVA-* acceptance; CI runs the five lanes.
