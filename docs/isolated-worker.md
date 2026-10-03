# Isolated worker execution (spec §127, issue #88)

CortexJ normally loads native runtimes (ONNX Runtime, llama.cpp, DJL) directly
into the platform JVM. That is fast, but a native crash, a native OOM or an
`abort()` inside a runtime library takes the whole platform down with it. The
isolated worker mode moves a runtime into a **child JVM**: the parent keeps a
thin proxy, the child hosts the real `RuntimeProvider`, and the two talk a
newline-delimited JSON protocol over the child's stdin/stdout. A crash now
kills only the child; the parent detects it, reports a typed error, tears the
process down with the destroy ladder and respawns on the next use.

Two modules implement this:

| Module | Artifact | Side | Role |
|---|---|---|---|
| `worker/` | `cortexj-worker` | child | `io.cortexj.worker.WorkerHost` - hosts one `RuntimeProvider` and serves the protocol |
| `runtimes/isolated/` | `cortexj-runtime-isolated` | parent | `IsolatedRuntimeProvider` - spawns the child and proxies the runtime |

## Opting in

Worker mode is **opt-in and per target runtime**. The isolated provider is
deliberately *not* registered via `META-INF/services` - it must never appear
for users who did not ask for it. Wire it programmatically:

```java
CortexJ platform = CortexJ.builder()
        .runtimeProvider(new IsolatedRuntimeProvider("onnx"))   // hosts the "onnx" runtime in a child JVM
        .build();
```

The provider id is the child's runtime id prefixed with `worker-` (e.g.
`new IsolatedRuntimeProvider("onnx")` registers as `worker-onnx`), so the
planner sees it as a distinct runtime next to the in-process one. The
handshake verifies the child actually hosts the requested runtime id; a
mismatch fails the probe with a typed error.

The child needs the target runtime on its classpath, so add the runtime
module as a dependency of your application (e.g. `cortexj-runtime-onnx`)
alongside `cortexj-runtime-isolated` and `cortexj-worker`.

## Process model

* Spawn: `$(java.home)/bin/java -cp $(java.class.path) io.cortexj.worker.WorkerHost <runtimeId>`.
  The classpath is taken from `java.class.path` of the parent JVM; override with
  the system properties `cortexj.worker.classpath` and `cortexj.worker.java` if
  your packaging needs an explicit worker classpath/executable. Under Maven
  Surefire this works too: the forked test JVM's `java.class.path` (including
  the manifest-only booter jar Surefire uses by default) carries all test and
  dependency classes, which is how the isolated tests spawn real children.
* Environment: the child gets an **allowlist** of the parent environment
  (`PATH`, `HOME`, `TMPDIR`, `TEMP`, `TMP`, `LANG`, plus `LC_*` and `JAVA_*`
  variables) - the same hygiene pattern as the llama-server launcher. The
  environment is never logged.
* Working directory: a fresh temp directory (`cortexj-worker-<runtime>-*`).
* stderr: the child's stderr (worker logs, native runtime logs) is drained by
  a daemon thread into the parent's JUL logger at `FINE` under the
  `io.cortexj.runtime.isolated.WorkerChannel` logger. It never leaks into the
  protocol.
* stdout: **reserved for the protocol.** A hosted runtime that prints to
  stdout corrupts the wire (the parent skips non-JSON lines with a warning).
  File bugs against runtimes that do this, or silence them via their own
  configuration.
* Teardown: `destroy()` -> `waitFor(10s)` -> `destroyForcibly()` ->
  `waitFor(5s)` (the process ladder from `ProcessLlamaServerLauncher`), after
  a graceful `shutdown` op with a short grace period.

## Protocol (v1)

UTF-8, one JSON object per line. Requests carry an integer `id`; responses
echo it. Stream events are objects with an `event` field carrying the id of
the stream request.

On startup the child emits the handshake before reading anything:

```json
{"event":"hello","protocol":"1","runtimeId":"stub"}
```

(or `{"event":"hello","protocol":"1","runtimeId":"x","error":{"code":"CORTEXJ-RUNTIME-3002","message":"..."}}`
followed by exit code 1 when no provider with the requested id is on the
classpath).

### Ops (parent -> worker)

| Op | Request | Response |
|---|---|---|
| `probe` | `{"id":N,"op":"probe","offline":false,"configuration":{}}` | `{"id":N,"ok":true,"descriptor":{...},"available":true,"health":"AVAILABLE","message":"..."}` |
| `open` | `{"id":N,"op":"open","descriptor":{...model...},"artifact":{...}|null,"artifactPath":"...","device":"CPU","options":{},"headers":{}}` | `{"id":N,"ok":true,"runtimeOpened":true}` |
| `load` | `{"id":N,"op":"load","descriptor":{...},"artifactPath":"...","device":"CPU","options":{}}` | `{"id":N,"ok":true,"modelToken":"m1","capabilities":{...},"textGeneration":true,"embedding":true}` |
| `execute` | `{"id":N,"op":"execute","modelToken":"m1","request":{"inputs":[...],"parameters":{},"metadata":{}}}` | `{"id":N,"ok":true,"response":{"outputs":[...],"metadata":{}}}` |
| `generate` | `{"id":N,"op":"generate","modelToken":"m1","request":{...generation...}}` | `{"id":N,"ok":true,"response":{"text":"...","finishReason":"STOP","usage":{...},"metadata":{}}}` |
| `embed` | `{"id":N,"op":"embed","modelToken":"m1","request":{"texts":[...]}}` | `{"id":N,"ok":true,"response":{"vectors":[[...],...]}}` |
| `stream` | `{"id":N,"op":"stream","modelToken":"m1","request":{...generation...}}` | `{"id":N,"ok":true}` then events (below) |
| `cancelStream` | `{"id":N,"op":"cancelStream","streamId":S}` | `{"id":N,"ok":true}` (idempotent for unknown/finished streams) |
| `closeModel` | `{"id":N,"op":"closeModel","modelToken":"m1"}` | `{"id":N,"ok":true}` (idempotent) |
| `close` | `{"id":N,"op":"close"}` | `{"id":N,"ok":true,"runtimeClosed":true}` (child stays alive, ready to reopen) |
| `shutdown` | `{"id":N,"op":"shutdown"}` | `{"id":N,"ok":true}`, then the child closes the hosted runtime and exits 0 |

Any op can fail: `{"id":N,"ok":false,"error":{"code":"CORTEXJ-...","message":"...","remediation":"...","details":{}}}`.
A malformed line yields the same error shape with the request id when the JSON
was parseable enough to carry one, or id `0` otherwise - never a crash loop.
Error codes are preserved across the process boundary and reconstructed into
the matching typed exception on the parent (`CORTEXJ-RUNTIME-*` ->
`RuntimeUnavailableException`, `CORTEXJ-LOAD-*` -> `ModelLoadException`,
`CORTEXJ-NATIVE-*` -> `NativeRuntimeException`, else `InferenceException`).

### Stream events (worker -> parent)

```json
{"event":"token","id":S,"text":"..."}
{"event":"complete","id":S,"finishReason":"STOP","usage":{"inputTokens":3,"outputTokens":3}}
{"event":"error","id":S,"code":"CORTEXJ-INFER-9002","message":"..."}
```

The parent pumps these into a `PushTokenStream` (streaming module, same
pattern as the remote runtimes); a subscription `cancel()` sends
`cancelStream` and the worker aborts the upstream stream.

### Field mapping

* **Runtime descriptor** (probe response): full descriptor as JSON -
  `runtimeId`, `version`, `formats[]`, `devices[]`, `tasks[]`,
  `minJavaFeatureVersion`, `streamingSupported`, `structuredOutputNative`,
  `nativeRuntime`, `threadSafety`, `maturity`, `metadata{}`. The parent
  re-ids it (`worker-<runtimeId>`), forces `threadSafety` to `STATELESS`
  (execution is a stateless pipe) and adds `worker.target`/`worker.mode`
  metadata. This is a superset of the minimal
  `{runtimeId,version,format,device,task,maturity}` shape.
* **Model descriptor** (open/load): `canonicalId`, `revision`, `architecture`,
  `license`, `parameterCount`, `contextLength`, `tasks[]`, `trustNote`.
* **Artifact** (optional, open/load): `id`, `format`, `sizeBytes`,
  `checksum{algorithm,value}`, `metadata{}`.
* **InferenceRequest**: `inputs[]`, `parameters{}`, `metadata{}` (rebuilt via
  `InferenceRequest.builder()`).
* **InferenceResponse**: `outputs[]`, `metadata{}`.
* **GenerationRequest** (generate/stream): `prompt`, `messages[{role,content}]`,
  `temperature`, `maxTokens`, `topP`, `topK`, `seed`, `stopSequences[]`.
* **GenerationResponse**: `text`, `finishReason`, `usage{inputTokens,outputTokens}`,
  `metadata{}`.
* **EmbeddingRequest/Response**: `texts[]` / `vectors[[floats]]`.
* **Load response extras**: `capabilities{tasks[],streamingSupported,structuredOutputSupported,maxConcurrentRequests}`
  plus boolean `textGeneration`/`embedding` telling the parent which capability
  interfaces the child model implements (the parent-side proxy advertises
  exactly those via a JDK dynamic proxy).

## Failure and lifecycle semantics

* **Blocking requests** wait up to 5 minutes (default `300_000 ms`,
  configurable via `cortexj.worker.request.timeout.millis`). A timeout fails
  the request typed but does not kill the child; a late response is dropped.
* **Child death mid-request** (crash, `kill -9`, OOM): every pending request
  fails with `InferenceException` (`CORTEXJ-INFER-9001`) whose message and
  remediation name the worker process; active streams fail the same way. The
  channel tears the process down with the ladder and the next `probe()` /
  `open()` respawns a fresh child automatically.
* **Session model (v1)**: the worker hosts exactly one open runtime session.
  Two concurrent `open()`s fail the second loudly (typed). Model handles that
  outlive a child crash fail typed on their next use - reopen a session after
  a crash.
* **`ModelRuntime.close()`** closes all model tokens, sends `close` when it is
  the last live session, and shuts down the (owned) IO executor. The child
  process stays up for fast reuse.
* **`IsolatedRuntimeProvider.close()`** (the provider is `AutoCloseable`)
  closes every open session, sends `shutdown`, and applies the destroy
  ladder. Idempotent. Even without it, no orphans survive: workers exit on
  the `shutdown` op, on stdin EOF (parent JVM gone) or on their own
  `System.exit`.

## v1 limitations (deliberate cuts)

* **No environment/hardware forwarding**: the child probes with its own local
  machine profile (it runs on the same host); only `offline` and the probe
  `configuration` map cross the wire.
* **Model descriptor modalities and artifact lists** are not forwarded (the
  artifact path and the selected artifact's metadata are).
* **`GenerationRequest.runtimeOptions`** (planner `RuntimeOptions`) has no wire
  mapping; per-request knobs like `temperature`/`maxTokens` do.
* **Only `TextGenerationCapability` and `EmbeddingCapability`** are proxied;
  reranking, vision, STT/TTS and structured-output capabilities are not (a
  child model implementing them simply does not expose them through the
  proxy; `execute` still reaches any task the child model handles itself).
* **One hosted session per worker** (see above).
* **Single-request serialization on the worker main thread**: ops are handled
  one line at a time; streaming events are pumped concurrently. The parent
  descriptor reports `STATELESS`, but the practical concurrency ceiling per
  child is one blocking op at a time plus active streams.
* **`executeAsync`** parallelism happens on the parent side (executor), so
  concurrent executes still serialize in the child in v1.
* **JPMS/modular-image launches** are unsupported: the spawn relies on a
  real `-cp` classpath (`java.class.path`); override with
  `cortexj.worker.classpath` for custom layouts.

## Ops notes

* Log noise from the child shows up under logger
  `io.cortexj.runtime.isolated.WorkerChannel` at `FINE` (`[worker-stderr] ...`).
  Enable it when diagnosing a worker that refuses to boot.
* A worker that dies repeatedly under load is usually the native runtime
  itself: probe messages and the typed inference errors preserve the child's
  error codes, so alerting/fallback tooling treats it like any other runtime
  failure.
* Tests spawn real child JVMs through `java.class.path` (works under Surefire)
  and need no native ONNX runtime - the isolated module's test sources
  register a stub `RuntimeProvider` (id `stub`) that the child hosts via
  ServiceLoader.
