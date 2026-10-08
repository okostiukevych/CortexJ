# Writing a RuntimeProvider

This guide covers the RuntimeProvider SPI (spec §15, §957+ sections): lifecycle,
capability declaration, probe behavior, support evaluation, error mapping, native
resources, thread-safety, and TCK execution.

## 1. Implement the SPI

```java
public final class MyRuntimeProvider implements RuntimeProvider {

    private final RuntimeId id = RuntimeId.of("my-runtime");

    @Override
    public RuntimeId id() { return id; }

    @Override
    public RuntimeDescriptor descriptor() {
        return RuntimeDescriptor.builder(id)
                .version("1.0.0")
                .format(ArtifactFormat.GGUF)          // formats you can execute
                .device(DeviceType.CPU)               // devices you can run on
                .task(TaskCapability.TEXT_GENERATION) // tasks you support
                .minJavaFeatureVersion(11)
                .streamingSupported(true)
                .nativeRuntime(true)                  // honest!
                .threadSafety(ThreadSafety.SESSION_SCOPED)
                .maturity(RuntimeDescriptor.Maturity.BETA)
                .build();
    }
    // probe / evaluate / open ...
}
```

## 2. Probe honestly (§39)

`probe(context)` answers "can this runtime run HERE, right now?" — never assume.

```java
@Override
public RuntimeProbeResult probe(RuntimeProbeContext context) {
    if (!nativeLibraryPresent()) {
        NativeRuntimeException failure = new NativeRuntimeException(
                ErrorCode.NATIVE_LIBRARY_MISSING,
                "my-runtime native library is not installed",
                "Install libmyruntime 1.2 and point MY_RUNTIME_LIB at it, or use the onnx runtime.");
        return RuntimeProbeResult.unavailable("native library missing", failure);
    }
    return RuntimeProbeResult.available("my-runtime 1.2 ready");
}
```

Health values: `AVAILABLE`, `DEGRADED`, `UNAVAILABLE`, `MISCONFIGURED`. The planner
caches probe results per platform instance and uses them in planning.

## 3. Evaluate candidates

`evaluate(request)` decides whether a concrete artifact can run on a concrete device:

```java
@Override
public RuntimeSupport evaluate(RuntimeEvaluationRequest request) {
    if (request.artifact() == null
            || request.artifact().format() != ArtifactFormat.GGUF) {
        return RuntimeSupport.unsupported(PlanReason.negative(
                ReasonCode.FORMAT_UNSUPPORTED, "my-runtime only executes GGUF"));
    }
    return RuntimeSupport.full(DeviceType.CPU);
}
```

Every rejection must carry a `PlanReason` — rejected candidates surface verbatim in
plans and support reports (spec §26).

## 4. Open and load

```java
@Override
public ModelRuntime open(RuntimeOpenRequest request) {
    RuntimeProbeResult probe = probe(new RuntimeProbeContext(
            request.environment(), request.hardware(), false, request.platformConfiguration()));
    if (!probe.isUsable()) {
        throw probe.error();          // honest typed error
    }
    return new ModelRuntime() { ... return new MyRuntimeModel(...); ... };
}
```

## 5. Map errors (§55)

Throw typed CortexJ exceptions with remediation — never raw runtime exceptions:

- load failures → `ModelLoadException` (`CORTEXJ-LOAD-*`)
- inference failures → `InferenceException` (`CORTEXJ-INFER-*`)
- native problems → `NativeRuntimeException` (`CORTEXJ-NATIVE-*`)

## 6. Native resource lifecycle (§42)

`ModelRuntime.close()` and `RuntimeModel.close()` must release everything native.
Handles are closed by the platform or by try-with-resources on the user side.

## 7. Thread safety (§38.4)

Declare `ThreadSafety` accurately. `SESSION_SCOPED` makes the platform serialize access
per handle with a lock; `THREAD_SAFE` lets callers run concurrently.

## 8. Register + run the TCK

`src/main/resources/META-INF/services/io.cortexj.spi.runtime.RuntimeProvider`:

```text
com.example.MyRuntimeProvider
```

Then extend the contract kit and run it:

```java
class MyProviderTckTest extends RuntimeProviderContract {
    // provider(), plus your provider-specific honest-behavior tests
}
```

The TCK validates descriptor sanity, probe reporting, evaluation reasons,
ServiceLoader registration, lifecycle close idempotency, typed failed-load
behavior with the provider staying usable, and streaming contracts (single
subscription, cancel semantics, at-most-once terminal). Contracts that need a
loadable artifact skip loudly via assumeTrue when none is available. See
`cortexj-tck` sources for the exact contract.

## 9. Optional JInfer provider conditions (spec v3.0 §0/§19, issue #160)

`cortexj-runtime-jinfer` does not exist yet (JInfer is absent from Maven
Central) — no code is possible. When it becomes possible, all of the
following must hold before the adapter ships:

- baseline follows the actual JInfer release (current target: Java 25+);
  starters for Java 8/11/17/21 must NOT include it;
- public API + license compatibility verified and recorded here;
- JInfer types encapsulated inside the adapter (planner sees only
  descriptors, presence + compatibility gated);
- full Runtime Provider TCK green;
- version matrix published (CortexJ x JInfer), kept current:

| CortexJ | JInfer | Status |
|---|---|---|
| 0.1.x | n/a (absent from Central) | blocked |
