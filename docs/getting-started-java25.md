# Getting Started — Java 25

On Java 25 use `cortexj-starter-java25`. This is the reference platform line: the CLI,
all adapters, and the full reactor are verified on JDK 25 first.

## 1. Add the starter

```xml
<dependency>
    <groupId>io.cortexj</groupId>
    <artifactId>cortexj-starter-java25</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## 2. Full feature set

Everything from earlier guides plus:

- newest platform module tuning (I/O executors, GC-friendly event flow);
- all adapter modules (`cortexj-spring-ai`, `cortexj-langchain4j`,
  `cortexj-observability`);
- the complete CLI (`doctor`, `runtimes`, `hardware`, `inspect`, `plan`, `pull`, `run`,
  `server`, `convert`, `cache list`, `cache clean`, `bundle create/verify`, lockfile
  flags).

## 3. Verify your environment

```bash
java -jar cortexj-cli-0.1.0-SNAPSHOT.jar doctor
```

`doctor` prints: Java version, detected platform module, hardware profile, runtime
health (ONNX, llama.cpp, DJL, remote), and actionable recommendations.

## 4. Wrong starter / wrong JVM

Boot-time validation (spec AC-JAVA-004) fails with a typed error, for example running
the Java 25 starter on Java 8:

```text
CORTEXJ-CONF-0002: This assembly requires Java 25 or newer, but the runtime is Java 8.
Remediation: use cortexj-starter-java8 for this JVM.
```

## 5. Example — plan before execution

```java
ExecutionPlan plan = platform.planner().plan(
        ExecutionRequest.forModel(ModelReference.of("Qwen/Qwen3-8B")));
System.out.println(plan.explanation());   // explainable, deterministic
```
