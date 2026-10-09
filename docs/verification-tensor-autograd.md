# DoD — Tensor/Autograd verification

Date: 2026-10-08. `mvn clean verify` BUILD SUCCESS. Modules:
`tensor-api` (3 tests), `tensor-cpu`, `tensor-vector` (9 conformance
tests), `autograd` (18 tests: 7 differential + 4 policies + Shape and
contract tests).

1. Stable Tensor/Shape/DType/Device API — public contracts with the
   backend SPI behind them.
2. Reference CPU backend — scalar `CpuBackend`, correctness first.
3. At least one accelerated backend — `VectorBackend` over the JDK
   Vector API (incubator module isolated in the provider).
4. Core op conformance suite — 9 conformance tests: matmul/
   reductions/softmax/relu/elementwise/reshape/transpose parity plus
   sigmoid/tanh/gelu/layerNorm/gather/scatter parity between backends.
5. Deterministic shape/broadcast semantics — shapes fixed at creation,
   row-major offsets hand-computed in tests.
6. Explicit memory/resource lifecycle — close idempotent, closed
   tensors fail typed, views share storage, `setAll` for state loading.
7. Autograd gradient correctness suite — 7 differential tests vs
   finite-difference references within tolerance (linear/MLP/matmul).
8. Custom Module training demonstration — custom attention-style
   module authored in the test without framework changes, gradients
   flow into its parameters.
9. Serialization/state support — `stateDict`/`loadStateDict` with
   typed failures on missing/oversized parameters.
10. No Runtime dependency on Tensor/Autograd — `ArchitectureGuardTest`
    green (runtime/* never touches tensor/autograd/research).
