# Writing a Planner Policy

Planner policies (spec §27) customize soft scoring and rejection without touching the
planner itself. Hard constraints are built-in and cannot be bypassed by scores.

## 1. Implement the SPI

```java
public final class PrivacyPolicy implements ExecutionPolicy {

    @Override
    public String policyId() { return "privacy-no-remote"; }

    @Override
    public PolicyDecision evaluate(ExecutionCandidate candidate, PlanningContext context) {
        if (candidate.device() == DeviceType.REMOTE) {
            return PolicyDecision.reject(ReasonCode.USER_REQUIREMENT_CONFLICT,
                    "remote execution is forbidden by the privacy policy");
        }
        return PolicyDecision.neutral();
    }
}
```

## 2. Register

Programmatic (per deployment):

```java
CortexJ platform = CortexJ.builder()
        .executionPolicy(new PrivacyPolicy())
        .build();
```

`PolicyDecision` options:

- `neutral()` — no opinion;
- `reject(reasonCode, message)` — candidate is removed and reported with the reason;
- `scoreAdjustment(delta, reasonCode, message)` — soft scoring shift (spec §25);
  adjustments can never override hard constraints (Java version, memory limit,
  required runtime, offline rules...).

## 3. PlanningContext

Policies receive the environment profile, hardware profile, the user's
`ExecutionRequest`, and a cache lookup — enough to encode rules like "prefer
quantized GGUF on machines under 16 GB", "never schedule degraded runtimes for
production models", or "require q6_k or better for the finance use case".

## 4. Quality profiles (§90)

`ExecutionRequest.qualityProfile(QualityProfile.MEMORY_FIRST)` maps to built-in
scoring rules (`QualityProfileRule`); policies compose with them and their effects
are visible in the plan explanation — chosen quantization is never hidden.

## 5. Testing

Policies are pure functions of (candidate, context) — unit test them directly with
synthetic candidates. For integration, register the policy on a platform built with
test providers (see `planner/src/test` for fixtures) and assert the plan outcome and
the explanation text.

---

## Benchmark hints (spec 126)

Besides writing a custom `ExecutionPolicy`, a team can encode measured
performance into planning decisions with a locally supplied hints file —
no code, no benchmark learning, fully deterministic.

Drop `benchmarks.properties` into the platform working directory
(`<CORTEXJ_HOME>`, by default `~/.cortexj`), or point
`-Dcortexj.benchmarks.file=<path>` at a file elsewhere:

```properties
# bench.<runtime>,<family>,<quantization>,<device>,<metric>=<value>
# runtime / family / quantization may be '*' (wildcard); device is cpu/gpu/npu/*

bench.llamacpp,qwen3,*,cpu,throughputTokensPerSecond=85
bench.onnx,qwen3,*,cpu,throughputTokensPerSecond=40
bench.onnx,qwen3,*,cpu,latencyMillis=180
bench.llamacpp,qwen3,*,cpu,memoryGb=5.2
```

- `family` matches as a lowercase substring of the model reference
  (`qwen3` matches `Qwen/Qwen3-8B`)
- metrics: `throughputTokensPerSecond` (higher better), `latencyMillis`
  (lower better), `memoryGb` (lower better)
- scoring is a bounded, deterministic function of the file alone: throughput
  contributes up to +15 points normalized to the file's maximum, latency up
  to +10 normalized to the file's minimum, memory up to +5 normalized to the
  file's minimum — the same file always produces the same plan
- applied adjustments appear in the plan explanation
  (`benchmark:throughput 85.0 t/s (+15)`)
- malformed or unknown entries are ignored with a WARNING (never a plan
  failure); an unreadable file is not applied
