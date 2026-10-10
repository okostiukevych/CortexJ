# Multi-JDK verification (DoD item 1)

Date: 2026-10-10. `mvn clean verify` per LTS on this machine
(JDK 8u451, 11, 17, 21, 25.0.4; macOS arm64, no GPU).

| JDK | Modules | Tests | Failures | Notes |
|---|---|---|---|---|
| 8 | 31 (base only) | 524 | 0 | tracks 17+/21+ excluded by profile (ADR-017) |
| 11 | 33 | 539 | 0 | + java11 platform/starter |
| 17 | 17+ tracks | 603 | 0 | Lab/Data/Experiments/Train/notebook active |
| 21 | 21+ tracks | 639 | 0 | Tensor/Autograd/Research/benchmarks/multinode active |
| 25 | all | 669 | 0 | + jinfer/runtime-25/CLI |

Skips are loud (gated lanes, no-model contracts). GPU lanes stay
gated (#91): CPU-only execution on this machine.

Findings fixed on the way:

- Track modules lived in the default reactor and broke the JDK 8
  build (stale v61 classes, then `release 21 not supported`):
  17+ tracks moved to the `jdk17plus` profile, 21+ tracks to
  `jdk21plus` (ADR-017 enforced by the build itself).
- `train-distributed` and `lab/notebook` declared release 21 while
  living on the 17+ track: both corrected to 17.
