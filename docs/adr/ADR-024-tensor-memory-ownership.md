# ADR-024: Tensor memory ownership model

Date: 2026-10-08. Status: accepted (0.x).

## Context

The tensor API must have clear ownership/lifecycle for memory. The
reference CPU backend is GC-managed, but the contract must hold for
native backends (FFM) arriving later.

## Decision

- Tensors are `AutoCloseable`; `close` is idempotent. A closed tensor
 fails typed on use ("tensor is closed"), never segfaults.
- Ops produce results that OWN their storage (fresh allocation); views
 (`slice`, `reshape`) SHARE the parent storage with explicit bounds.
 Closing a view never frees shared data while the parent (or another
 view) is alive — the reference backend keeps a shared storage
 reference; native backends must refcount.
- Explicit dtype/device transfer only through named ops; implicit
 coercion is rejected typed.
- Metadata (shape/dtype/device) is immutable after creation.

## Consequences

- The autograd graph holds references to tensors it needs; released
 intermediates are guarded by the retention policy (ADR-026).
- Native backends inherit the same close/view contract and add
 refcounting; no API change.
