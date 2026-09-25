# Getting Started — Java 21

On Java 21 use `cortexj-starter-java21`. The platform module for this LTS uses virtual
threads for I/O orchestration (downloads, remote calls) behind the same API.

## 1. Add the starter

```xml
<dependency>
    <groupId>io.cortexj</groupId>
    <artifactId>cortexj-starter-java21</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## 2. What the platform module changes

- `PlatformModule.createIoExecutor()` returns a virtual-thread-per-task executor used
  for downloads and remote inference (`generateAsync`, SSE streaming).
- Everything else (planner, cache, repositories) is identical across LTS lines.

## 3. Async inference

```java
CompletionStage<InferenceResponse> future =
        model.executeAsync(InferenceRequest.single("summarize: ..."));
```

On Java 21 the remote runtime performs the call on a virtual thread, so large numbers
of concurrent sessions do not exhaust the platform thread pool.

## 4. Structured concurrency notes

CortexJ does not create uncontrolled threads (spec §38.5): every subsystem uses the
injected `ExecutorService` from `CortexJBuilder.executor(...)` or the platform module's
I/O executor, which is closed with the platform.
