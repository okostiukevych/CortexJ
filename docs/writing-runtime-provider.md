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

Shipped as `cortexj-runtime-jinfer` (Java 25+ only, `com.qxotic:jinfer-chat:0.3.1`,
Apache-2.0 — verified on Maven Central 2026-10-08). All of the following hold:

- baseline follows the actual JInfer release (current target: Java 25+);
  starters for Java 8/11/17/21 must NOT include it (verified: no `jinfer`
  reference under `starters/` or `platform/`);
- public API + license compatibility verified and recorded here: builder-based
  `ChatEngine`/`Models` API is encapsulated inside the adapter (planner sees
  only descriptors, presence + compatibility gated);
- full Runtime Provider TCK green (`JInferProviderTckTest`; load/streaming
  contracts skip loudly without a local GGUF model file);
- version matrix published (CortexJ x JInfer), kept current:

| CortexJ | JInfer | Status |
|---|---|---|
| 0.1.x | 0.3.1 (`com.qxotic:jinfer-chat`, Apache-2.0) | available (Java 25+, GGUF chat/embedding/reranking) |

Live verification (2026-10-08, JDK 25, CPU-only, scratch harness outside
the repo; model files deleted after the run):
- chat `LiquidAI/LFM2.5-350M-GGUF:Q8_0` → TEXT_GENERATION, `generate`
  returns text + STOP + token usage; `execute()` consistent;
- `Qwen/Qwen3-Embedding-0.6B-GGUF:Q8_0` → EMBEDDING, 1024-dim unit
  vectors; `newState(2048)` confirmed sufficient for short-text batches;
- `mradermacher/Qwen3-Reranker-0.6B-GGUF:Q8_0` → RERANKING, correct
  ordering (0.9988 vs 0.00007); `defaultInstruction()` confirmed as the
  instruction mapping;
- dispatch subtlety found and fixed: the embedding and reranking loaders
  of one family accept each other's files, so order alone misclassifies —
  `Models.retrieval()` now decides, declared model task breaks ties;
- token-granular streaming over `ReplySink` (CONTENT channel): 38 tokens,
  exactly one terminal, second subscription rejected, immediate cancel
  delivers exactly one terminal;
- wrong-kind capability use and missing files both fail typed
  (`INFERENCE_UNSUPPORTED_TASK` / `LOAD_FAILED`).

Follow-ups (explicit, not blockers): none open — the two items from the
previous revision are closed by the run above.
