# ADR-027: Distributed collective abstraction

Date: 2026-10-08. Status: accepted (Phase A).

## Context

Scale-out training needs gradient synchronization across workers, but
the inference planner must stay decoupled from training topology
(topology planning lives in a separate planner).

## Decision

- `cortexj-train-distributed` owns the collective SPI
 (`Collective`/`AllReduce`) and the phased implementation:
 - Phase A: single-node data-parallel — the batch is split across N
 in-process workers, per-shard gradients are averaged
 (sum / workerCount) and applied; parity-tested against the
 equivalent single-worker full batch;
 - Phase B: multi-node data-parallel with real collectives and
 fault/retry semantics;
 - Phase C: tensor/pipeline parallel, sharded checkpoints.
- The topology planner is a separate component reusing
 capability/device abstractions; it never lives inside the inference
 planner.

Scope-gate answers: problem = data-parallel gradients without a
distributed framework; existing engines insufficient alone at the
collective/topology layer; minimal solution = Phase A; acceptance =
gradient parity test; Runtime impact = none; native cost = none
(in-process).

## Consequences

- Phase B/C require their own ADRs before implementation.
- The inference planner source stays untouched.
