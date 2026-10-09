# Paper experiment template

Copy this structure for a reproducible paper experiment. Every block
answers one question a reviewer asks.

```text
paper-experiment/
├── README.md            # what, why, how to reproduce
├── data/
│   ├── cards.md         # dataset card: fingerprint, source, license
│   └── (raw data)
├── src/
│   └── main/java/...    # the experiment (Module implementations)
├── experiments/         # run manifests (auto-captured)
├── model.lockfile       # pinned model/revision/artifact
└── results/
    └── manifest.properties  # final metrics + checksums
```

Reproducibility checklist:

- [ ] dataset fingerprint recorded (`Fingerprint.ofFrame`)
- [ ] model identity pinned (id + immutable revision)
- [ ] seeds captured for every stochastic step
- [ ] JDK/OS/backend versions captured (auto via Experiment)
- [ ] output artifact checksums recorded
- [ ] the run reproduces from a clean checkout

Cards template:

```text
Dataset: <fingerprint>
Source: <where from>
Rows: <count>
License: <SPDX or URL>

Model: <id@revision>
Intended use: <what it is for>
Limitations: <what it must not be used for>
License: <SPDX or URL>

Citation: <authors>. <title> (<year>). <venue>. <identifier>
```
