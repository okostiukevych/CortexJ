# P0 Runtime 1.0 — Definition of Done verification (spec v3.0 §108)

Date: 2026-10-08. Environment: JDK 25.0.4 (only JDK on the machine),
macOS arm64, network available, no GPU. Command: `mvn clean verify`
(this repo, full reactor).

Result: **BUILD SUCCESS, 43 modules, 132 test classes / 565 tests /
17 loud skips / 0 failures.** Prior round (42 modules, 548 tests) is in
`docs/verification-round-2026-10-08.md` (spotbugs zero, perf lanes,
CVE re-check, JPMS smoke) — not repeated here.

## §108 item by item

1. **Starters for Java 8/11/17/21/25** — all five package green in the
   reactor (`CortexJ Starter Java 8/11/17/21/25 SUCCESS`). Execution on
   older JDKs is NOT verifiable on this machine (only JDK 25); CI matrix
   lanes stay gated by #91.
2. **Shared public API compatibility matrix** — no released baseline
   exists yet (`cortexj.api.baseline=none`, `-Pcompat` skips cleanly by
   design); japicmp guard wired per #148. First matrix is due with the
   first release, not before.
3. **Legacy Java 8 demo without Python** — `examples/.../java8/
   LegacyApplication.java` present; `CortexJ Examples SUCCESS` on the
   Java 8 bytecode path. Live run on a real JDK 8 needs the CI lane (#91).
4. **Java 25 demo uses the optimized platform module** — `doctor`
   smoke (below) detects `cortexj-platform-java25` on JDK 25.
5. **ONNX + llama.cpp production adapters stable** — both modules
   green incl. concurrency/close-interrupt/cancel suites.
6. **Remote provider stable** — module green; `doctor` reports
   `openai-remote: degraded` against a dead endpoint (honest, typed).
7. **Spring AI adapter stable** — module + `Examples Spring AI` green.
8. **LangChain4j adapter stable** — module green.
9. **Planner deterministic** — `ExecutionPlannerTest`,
   `ValidPlanRateTest`, `UserOverrideAuditTest`, golden/lockfile suites
   green; no provider-order dependence (TCK registration contract).
10. **Plan serialization / lockfile** — `LockfileTest` green; schemas
    versioned per #159.
11. **Offline bundle** — covered by existing offline/bundle suites
    (green in this run); air-gap CLI path unchanged by this change.
12. **Security review** — `docs/security-review.md` + audit
    `reliability-audit-2026-10-05.md` stand; no new trust boundary
    introduced (JInfer loads local GGUF files only, no repo code exec).
13. **Observability integration** — `CortexJ Observability` module
    green, unchanged.
14. **Native resources freed** — close-idempotency TCK green for all
    bundled providers incl. the new `JInferProviderTckTest`
    (15 tests, 3 loud skips that require a local GGUF file).
15. **No mandatory JInfer/DJL/Spring in core** — proven: `mvn test
    -pl api,spi,core,planner,runtimes/onnx,runtimes/llamacpp -am`
    BUILD SUCCESS with `runtimes/jinfer` absent from the reactor;
    `grep jinfer starters/ platform/` empty; `ArchitectureGuardTest`
    green.

## §122 JInfer independence demonstration

- `JInferProviderTckTest.providerIsDiscoverableViaServiceLoader`
  green: with the artifact present, the planner sees a new `jinfer`
  candidate (probe: usable on Java 25, JVM-gated below 25).
- Without the artifact: item 15 above — application code unchanged,
  everything green.
- `doctor` smoke of the default CLI lists `onnx` available,
  `djl`/`llamacpp` unavailable with reasons, `openai-remote`
  degraded — JInfer correctly absent from the default distribution
  (optional artifact, §19.1).

## Explicitly NOT verified here (unchanged from the prior round)

Other JDKs, GPU paths, production traffic, CI execution itself (#91),
live-model JInfer inference (tracked: #163).
