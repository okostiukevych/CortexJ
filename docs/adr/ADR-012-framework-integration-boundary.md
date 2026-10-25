# ADR-012: Framework integration boundary

Date: 2026-10-25. Status: accepted (ratified retroactively).

## Context

Spring AI and LangChain4j users expect framework-native types on their side of
the boundary. Leaking framework types into the API would invert the dependency
and force framework-major-aligned Runtime releases.

## Decision

Integrations live in **separate modules** (`integrations/spring-ai`,
`integrations/langchain4j`) compiled on their frameworks' baselines (Java 17+).
They depend on the Runtime SPI; the shared core has **zero framework
dependencies**. Adapters map framework concepts (chat models, embeddings) onto
the public Runtime API; no framework class appears in shared signatures.

## Consequences

- Framework majors bump and release independently of Runtime.
- Core test matrix stays framework-free and fast.
- Example wiring (`examples/spring-ai`) shows the whole adapter surface.
