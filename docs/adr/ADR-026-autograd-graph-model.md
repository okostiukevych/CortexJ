# ADR-026: Autograd graph model

Date: 2026-10-08. Status: accepted (0.x).

## Context

Differentiable computation on the JVM needs gradient tracking with
correctness first; performance optimization is secondary until the
differential suite passes against a trusted reference.

## Decision

- `cortexj-autograd` (depends on tensor-api, never on Runtime):
 - `Variable` — tensor + `requiresGradient` + accumulated `.grad`;
 - tape-based graph: gradient-tracked ops (`add`, `mul`, `matmul`,
 `sum`, `mean`, `softmax`, `relu`, `sigmoid`) append nodes at
 forward time;
 - `backward` — seed with ones for a scalar loss, walk the tape in
 reverse, accumulate gradients into leaf variables;
 - `detach`, no-grad scope (`noGrad(Supplier)`), custom backward
 rules (explicit op registration), gradient hooks.
- Retention policy: the tape holds only what backward needs;
 `noGrad` skips recording; intermediate tensors referenced by the
 tape stay alive until `backward` completes or the tape is
 discarded.
- Correctness: the differential suite compares autograd gradients
 against finite-difference references within declared tolerance per
 op (trusted reference first).

## Consequences

- Ops outside the tracked set either declare custom backward rules or
 run under no-grad; no silent non-differentiable path.
- Memory policies (graph retention, release of intermediates) are
 explicit API, not hidden GC behavior.
