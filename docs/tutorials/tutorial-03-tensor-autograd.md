# Tutorial 3 — first tensor program and gradients (45 minutes)

You create tensors on two backends, check parity, then train a tiny
MLP with autograd. Every snippet runs against `cortexj-tensor-*` and
`cortexj-autograd` (see `TensorConformanceTest`,
`AutogradDifferentialTest`).

## 1. Create tensors on two backends

```java
TensorBackend cpu = new CpuBackend();
TensorBackend vector = new VectorBackend();
Tensor a = cpu.create(new double[]{1, 2, 3, 4}, Shape.of(2, 2), DType.F32, Device.CPU);
Tensor b = cpu.create(new double[]{5, 6, 7, 8}, Shape.of(2, 2), DType.F32, Device.CPU);
```

## 2. Compare backends (parity first)

```java
Tensor reference = cpu.matmul(a, b);
Tensor accelerated = vector.matmul(a, b);
// same shape, elementwise-equal within float tolerance
```

Raw backend numbers are never attributed to the platform; the JIT
outruns the Vector API path at small sizes on some machines
(documented in ADR-025).

## 3. Build an MLP with autograd

```java
Autograd autograd = new Autograd(BACKEND);
Variable w1 = autograd.variable(new double[]{0.3, -0.2}, Shape.of(2));
Variable b1 = autograd.variable(new double[]{0.1, 0.1}, Shape.of(2));
Variable w2 = autograd.variable(new double[]{0.5, -0.4}, Shape.of(2));
Variable x = autograd.constant(new double[]{1.0, 1.0}, Shape.of(2));

Variable hidden = autograd.relu(autograd.add(autograd.mul(w1, x), b1));
Variable output = autograd.mul(w2, hidden);
Variable loss = autograd.mean(output);
autograd.backward(loss);
// w1.grad(), w2.grad(), b1.grad() now hold the gradients
```

## 4. Trust the gradients

The differential suite compares every op's gradients against
finite-difference references within tolerance — run
`AutogradDifferentialTest` to see the contract. Custom backward rules
(`record`), detach, no-grad scopes and gradient hooks are covered by
`AutogradPoliciesTest`.

## 5. Author a module

See `ModuleAuthoringTest`: a custom attention-style scoring module
written in user code (no framework changes), with parameters,
state-dict round-trip, train/eval mode and a notebook-friendly repr.
