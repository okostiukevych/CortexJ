# ADR-006: Streaming abstraction

Date: 2026-10-25. Status: accepted (ratified retroactively). Spec: v3.0 §§37, 7.

## Context

`jdk.flow` is Java 9+; `reactor`/`RxJava` are third-party. A shared streaming
contract cannot depend on either without dropping the Java 8 baseline or
imposing a reactive stack.

## Decision

Define the platform's own streaming contract (`io.cortexj.stream.TokenStream`
class with backpressure semantics) in the shared API tier; reactive adapters
(`cortexj-streaming-flow`, `cortexj-streaming-*-reactor` equivalents) live in
Java 11+/17+ modules and are never referenced by shared signatures. Engines
are typed as `streamingSupported` in descriptors, not by adapter class.

## Consequences

- Java 8 code streams with no reactive dependency.
- Reactor users are one module away; core releases decouple from adapter
  releases.
- Backpressure/termination edge cases are contract-tested once in the shared
  tier, not per adapter.
