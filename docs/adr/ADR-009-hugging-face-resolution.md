# ADR-009: Hugging Face resolution

Date: 2026-10-25. Status: accepted (ratified retroactively). Spec: v3.0 §§14, 30.

## Context

Hub layouts (repo/file/revision API, gated models, tokens) are a resolver
concern; the rest of the platform must stay repository-agnostic to support
local, HTTP and future registries uniformly.

## Decision

Hugging Face is one `ModelRepositoryProvider` implementation
(`repositories/huggingface`) exposing the same contract as `local` and `http`:
revision-pinned resolution, explicit `TokenSupplier` for gated models, and the
repository trust policy of ADR-013. Adding mirrors is a provider concern;
planner/verifier logic stays unchanged.

## Consequences

- Users swap/chain repositories via SPI registration.
- Credential handling is explicit in the provider, never ambient in core.
- Hub API breakage is a module-local fix.
