# Verification Round 2026-10-08 — independent checks beyond code review

Executable verification the audits did not previously perform. Environment:
JDK 25.0.4 (only JDK on the machine), macOS arm64, network available, no GPU.

## 1. Full build — `mvn clean verify`: BUILD SUCCESS, all 42 modules

132 test classes / 548 tests / 14 loud skips / 0 failures on JDK 25.
Perf-tagged suites (`-Pperf`) run separately and green.

## 2. Static analysis — `mvn verify -Pspotbugs`: zero findings

Fixed on the way (all mine, all with regression cover):
- `DownloadManager` NP_NULL_ON_SOME_PATH (partial filename leaf).
- `CortexJServer` DLS dead store in the auth-throttle claim.
- `CortexJServer` IS2 inconsistent sync (volatile executors/admissions).
- `CortexJServer` stop-race: locals + 503 instead of NPE with a leaked
  stream permit.

## 3. Coverage (JaCoCo) — branch gaps in new code closed

Added: throwing-consumer terminal test, guard edge cases
(null/empty/unresolvable), download guard-block test, first_token
positive histogram test.

## 4. Load, measured (`-Pperf`)

- Server split proof: full stream occupancy + unary still 200;
  unary saturation refuses 503 (`CortexJServerLoadTest`).
- Planner: 2000 candidates in ~24 ms (budget 15 s); 1500-plan soak
  stable (`PlannerThroughputSoakTest`).
- Cache contention: 3200 gets + 400 puts in 120 ms, 0 errors.
- Parser fuzz: 900 noise inputs (670 typed, 0 escapes) + 401
  structured inputs (269 typed, 0 escapes). Fixed seed.
- JFR allocation profile of the planner soak: top app allocations are
  short-lived `ExecutionCandidate`/notes-map/String churn - no leak
  pattern, ~1.2 ms/plan for 400 candidates.

## 5. CVE re-check of pinned versions (2026-10-08)

- jackson-databind 2.21.7: patched line for CVE-2026-91776/91777-class
  DoS; our `readTree`-only usage not on the exploit path.
- onnxruntime 1.30.0: past the traversal fixes (>= 1.24.1), no newer
  CVE found.
- opentelemetry 1.66.0: past CVE-2026-45292 (>= 1.62.0).
- langchain4j-core 1.21.0: the 2026 CVEs sit in mariadb/pgvector/agentic
  modules, none on our dependency path (core only).
- spring-ai 1.0.0 -> **1.0.7** (CVE-2026-41713 fixed in the OSS line;
  our adapter uses neither advisors nor tool dispatch, so
  CVE-2026-59318 is not on our path).
- junit (test-only), maven-plugin-api/exec-plugin (provided/build):
  nothing applicable found.

## 6. Docs drift

README matrix aligned to reality (132/~548/14 + perf-lane note,
contracts + hardening suites mentioned). `langchain4j.md` 1.0.x ->
1.21.x. BOM completed with both shipped integrations.

## 7. JPMS module-path smoke (new)

`provides ... with` without the `io.cortexj.core` export resolves;
a scratch consumer module on `--module-path` finds
`DefaultPlatformFactory` via ServiceLoader at runtime. (Confirms the
#148 encapsulation change; also surfaced that consumers must declare
`uses` - `api` now declares it for jlink service binding.)
Scratch files live outside the repo (`/tmp/jpms-smoke`, not committed).

## 8. CI workflows

`actionlint` 1.7.7 clean after adding `.github/actionlint.yaml`
(self-hosted cuda/metal labels declared). Lanes remain gated by
decision (#91) - execution still requires enabling them.

## 9. Secret hygiene (found, not fixed - needs the owner)

- The git remote URL embeds a live PAT (`ghp_...` in `.git/config`):
  filesystem access = push access. Recommend switching the remote to
  a credential helper/SSH **and rotating the token** (it has appeared
  in tool transcripts this session).
- No secrets in git history (`git log -S ghp_` empty) or tracked files;
  the `sk-sentinel-*` string in tests is a labeled fake for the
  key-leak test, by design.

## Explicitly NOT verified here (unchanged)

Other JDKs (only JDK 25 on this machine), GPU paths (no runners),
production traffic, CI execution itself (#91).
