# Writing a RepositoryProvider

The repository subsystem (spec §14) is responsible ONLY for discovery and resolution
of model artifacts — it never executes models.

## 1. Implement the SPI

```java
public final class MyRepositoryProvider implements ModelRepositoryProvider {

    @Override
    public RepositoryId id() { return RepositoryId.of("my-repo"); }

    @Override
    public boolean supports(ModelReference reference) {
        return reference.value().startsWith("myrepo:");
    }

    @Override
    public ResolvedModel resolve(ModelReference reference, ResolutionContext context) {
        if (context.isOffline() && !isCachedLocally(reference)) {
            throw new ModelResolutionException(ErrorCode.MODEL_OFFLINE_UNRESOLVED,
                    "my-repo cannot resolve " + reference.value() + " offline",
                    "Pre-download the model or configure a local mirror.");
        }
        // 1. resolve to an IMMUTABLE revision (spec 14.4)
        String revision = lookupImmutableRevision(reference);
        // 2. build the descriptor (id, revision, architecture, tasks, trustNote...)
        ModelDescriptor descriptor = ModelDescriptor.builder(reference.value())
                .revision(revision)
                .task(TaskCapability.TEXT_GENERATION)
                .trustNote(null)   // set a note when YOU cannot vouch for the model
                .artifact(artifact)
                .build();
        // 3. list artifacts with locations the download manager can fetch
        List<ResolvedArtifact> artifacts = ...;
        return new ResolvedModel(descriptor, revision, id(), artifacts);
    }
}
```

## 2. Rules the planner depends on

- **Immutable revision** — a branch/tag must be converted to a hash the repository
  will serve byte-identically later (HF commits; HTTP uses ETag). Plans, cache keys,
  and lockfiles all anchor on `resolved.revision()`.
- **Honest sizes** — artifact `sizeBytes` feeds memory estimation (spec §29); when a
  source cannot provide sizes, return `0` and the planner applies conservative
  defaults.
- **Checksums** — provide `Checksum` when the source publishes one; the download
  manager verifies it and the cache refuses corrupted content.
- **Tokens** — use `context.tokenSupplier()` for authenticated sources; tokens are
  never logged (spec §112).
- **Offline** — fail with `CORTEXJ-MODEL-1003` in offline mode; the TCK asserts this.
- **Trust notes** — set `trustNote` on the descriptor for content you cannot vouch
  for; `TRUSTED_ONLY` deployments will refuse it (see
  [enterprise security](enterprise-security.md)).

## 3. Locations

`ResolvedArtifact.location()` is a `URI`: `file:` for local content (no download),
`https:`/`http:` for remote (downloaded via the platform download manager with
checksum validation, size limits, retries and progress events). `downloadHeaders()`
carries per-request auth headers.

## 4. Register + run the TCK

`src/main/resources/META-INF/services/io.cortexj.spi.repository.ModelRepositoryProvider`:

```text
com.example.MyRepositoryProvider
```

```java
class MyRepositoryTckTest extends RepositoryProviderContract {
    // provider(), knownReference(), unknownReference()
    // the contract asserts: precise supports(), immutable revision,
    // typed unknown-reference errors, offline behavior (CORTEXJ-MODEL-1003)
}
```

## 5. Existing implementations to study

- `cortexj-repository-local` — filesystem + `cortexj-model.json` manifests + bundles
- `cortexj-repository-huggingface` — HF resolution with commit pinning
- `cortexj-repository-http` — plain URL references with ETag revisions
