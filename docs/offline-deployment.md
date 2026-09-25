# Offline and Air-Gapped Deployment

CortexJ supports three levels of deployment isolation (spec §62):

1. **Offline mode** — no network resolution at all.
2. **Air-gap bundles** — portable, verifiable model directories.
3. **Lockfiles** — reproducible plans for strict deployments (see below).

## 1. Offline mode

```java
CortexJ platform = CortexJ.builder().offline(true).build();
```

or

```bash
java -Dcortexj.offline=true -jar cortexj-cli-*.jar plan <model>
```

Uncached artifacts fail with `CORTEXJ-MODEL-1003` ("...not cached and offline mode is
active") plus remediation. Remote runtime candidates are excluded.

## 2. Creating a bundle

```bash
java -jar cortexj-cli-*.jar bundle create Qwen/Qwen3-8B --out ./release-bundle
java -jar cortexj-cli-*.jar bundle create ./local-model.gguf --out ./release-bundle
```

The bundle directory contains:

- `cortexj-model.json` manifest — model id, resolved immutable revision, architecture,
  license, tasks, and per-artifact entries with **computed sha256** and size;
- all model artifacts (weights plus tokenizer/config files when published);
- checksums for every file.

Artifacts are re-used from the platform cache when present (`~/.cortexj/cache`).

## 3. Verifying a bundle before deployment

```bash
java -jar cortexj-cli-*.jar bundle verify ./release-bundle
```

Checks: manifest present and parseable, every artifact file exists, declared size
matches, declared sha256 matches (tampering is reported explicitly), artifact paths
stay inside the bundle (path-traversal safe). Exit code is non-zero on any issue.

## 4. Running from a bundle (offline)

A bundle is itself a local repository — point the platform at it:

```bash
java -Dcortexj.offline=true -jar cortexj-cli-*.jar run ./release-bundle --prompt "hi"
```

The local repository provider resolves the bundle offline via its manifest.

## 5. Lockfiles for reproducible plans

```bash
java -jar cortexj-cli-*.jar plan Qwen/Qwen3-8B-GGUF --lockfile-out release.lock
java -jar cortexj-cli-*.jar run Qwen/Qwen3-8B-GGUF --lockfile release.lock
```

`--lockfile` enforces `PINNED_PLAN`: the planner must reproduce the locked runtime and
artifact or fail with `CORTEXJ-PLAN-4002` and a full explanation. Revision drift is
detected separately (resolved revision must equal the locked revision).

## 6. Deterministic quantization conversion (spec 32)

```bash
java -jar cortexj-cli-*.jar convert ./release-bundle/model.gguf --to f16 --out ./converted
```

The bundled `gguf-float` converter rewrites F32 tensors as F16 (and back) with a
provenance manifest (sha256 of the output, converted tensor counts); originals stay
immutable.
