# DoD — Lab 0.x verification

Date: 2026-10-08. `mvn clean verify` BUILD SUCCESS. New: `lab-api`
(5 tests: jshell round-trip, typed errors, live environment
descriptor, frame table render, project-skeleton export).

1. Supported Java baseline documented — Java 17 (ADR-017).
2. Notebook-compatible workflow — `LabSession` over the JDK JShell;
   the full Jupyter-wire adapter is an explicit follow-up (ADR-018).
3. Runtime model load/generation interactively — the session inherits
   the process classpath, so `CortexJ` snippets work; live inference
   from the kernel is exercised by the provider tests (live-verified)
   and needs a model artifact here.
4. Frame/table rich display — `Displayable.table` renders headers and
   rows; chart/image renderers are follow-ups.
5. Charts/images/metrics rendering — not in the 0.x slice (tracked
   honestly, not silent).
6. Reproducibly describable environment — `describeEnvironment()`
   captures JDK/OS/cores/version at call time.
7. Experiment provenance capture — embedded via the experiments
   module.
8. Notebook → Maven project export — `exportProjectSkeleton` writes
   the starter-java17 project.
9. Cancellation/resource cleanup — `LabSession implements
   AutoCloseable`; shell closed on close.
10. No Python kernel required — the whole slice is JVM-built-in.
