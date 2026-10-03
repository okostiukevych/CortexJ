package io.cortexj.maven;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Build-time deployment verification (spec 93/94), decoupled from the Mojo so the
 * checks are unit-testable without a Maven runtime.
 */
public final class DeploymentVerifier {

    public static final class Result {

        public final boolean ok;
        public final List<String> messages;

        Result(boolean ok, List<String> messages) {
            this.ok = ok;
            this.messages = messages;
        }
    }

    private DeploymentVerifier() {
    }

    /**
     * Checks that the project declares the starter matching its bytecode target
     * (spec 94: "correct starter").
     */
    public static Result checkStarter(List<String> dependencyKeys, String compilerTarget) {
        List<String> issues = new ArrayList<String>();
        String found = null;
        for (String key : dependencyKeys) {
            if (key.startsWith("io.cortexj:cortexj-starter-java")) {
                found = key.substring("io.cortexj:".length());
                break;
            }
        }
        int target;
        try {
            target = Integer.parseInt(compilerTarget.trim());
        } catch (NumberFormatException invalid) {
            issues.add("cannot determine maven.compiler.release/target: '"
                    + compilerTarget + "'");
            return new Result(false, issues);
        }
        if (found == null) {
            issues.add("no cortexj-starter dependency found; for bytecode target "
                    + target + " add io.cortexj:cortexj-starter-java" + target);
            return new Result(false, issues);
        }
        int starterLine = starterLineOf(found);
        if (starterLine < 0) {
            issues.add("unrecognized starter artifact: " + found);
            return new Result(false, issues);
        }
        if (starterLine != target) {
            issues.add("starter/bytecode mismatch: " + found
                    + " does not match maven.compiler target " + target
                    + "; use io.cortexj:cortexj-starter-java" + target);
            return new Result(false, issues);
        }
        List<String> ok = new ArrayList<String>();
        ok.add("starter matches bytecode target: " + found);
        return new Result(true, ok);
    }

    /**
     * Checks a lockfile is parseable and structurally complete (spec 94: "pinned
     * model bundle / checksum manifest").
     */
    public static Result checkLockfile(File lockfile) {
        List<String> issues = new ArrayList<String>();
        if (lockfile == null || !lockfile.isFile()) {
            issues.add("lockfile not found: " + (lockfile == null ? "null" : lockfile.getPath()));
            return new Result(false, issues);
        }
        try {
            io.cortexj.planner.ModelLockfile parsed =
                    io.cortexj.planner.ModelLockfile.readFrom(lockfile.toPath());
            if (parsed.model() == null || parsed.model().trim().isEmpty()) {
                issues.add("lockfile has no model entry");
            }
            if (parsed.revision() == null || parsed.revision().trim().isEmpty()) {
                issues.add("lockfile has no revision entry");
            }
        } catch (Exception malformed) {
            issues.add("lockfile is not valid: " + malformed.getMessage());
        }
        if (!issues.isEmpty()) {
            return new Result(false, issues);
        }
        List<String> ok = new ArrayList<String>();
        ok.add("lockfile structure is valid");
        return new Result(true, ok);
    }

    /**
     * Cross-checks a lockfile against an air-gap bundle when both are configured
     * (spec 94 + audit #60): the lockfile sha256 pin must match the sha recorded
     * in the bundle manifest for the pinned artifact.
     */
    public static Result checkLockfileAgainstBundle(File lockfile, File bundle) {
        List<String> issues = new ArrayList<String>();
        if (lockfile == null || bundle == null) {
            return new Result(true, new ArrayList<String>());
        }
        if (!lockfile.isFile() || !bundle.isDirectory()) {
            return new Result(true, new ArrayList<String>());
        }
        io.cortexj.planner.ModelLockfile pinned;
        try {
            pinned = io.cortexj.planner.ModelLockfile.readFrom(lockfile.toPath());
        } catch (Exception malformed) {
            issues.add("lockfile is not valid: " + malformed.getMessage());
            return new Result(false, issues);
        }
        java.nio.file.Path manifestPath = bundle.toPath()
                .resolve(io.cortexj.repository.local.bundle.ModelBundler.MANIFEST_FILE);
        if (!java.nio.file.Files.isRegularFile(manifestPath)) {
            issues.add("bundle has no " + io.cortexj.repository.local.bundle.ModelBundler.MANIFEST_FILE
                    + " manifest to cross-check against the lockfile");
            return new Result(false, issues);
        }
        try {
            com.fasterxml.jackson.databind.JsonNode manifest = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(manifestPath.toFile());
            String manifestId = manifest.path("id").asText(null);
            if (manifestId != null && pinned.model() != null
                    && !pinned.model().equals(manifestId)) {
                issues.add("lockfile model '" + pinned.model()
                        + "' does not match bundle model '" + manifestId + "'");
            }
            if (pinned.revision() != null) {
                String manifestRevision = manifest.path("revision").asText(null);
                if (manifestRevision != null && !pinned.revision().equals(manifestRevision)) {
                    issues.add("lockfile revision '" + pinned.revision()
                            + "' does not match bundle revision '" + manifestRevision + "'");
                }
            }
            if (pinned.pinsArtifact() && pinned.artifactSha256() != null) {
                String pinnedFile = pinned.artifactFile();
                String matchSha = null;
                boolean found = false;
                for (com.fasterxml.jackson.databind.JsonNode entry : manifest.path("artifacts")) {
                    if (pinnedFile == null || pinnedFile.equals(entry.path("file").asText())) {
                        found = true;
                        matchSha = entry.path("sha256").asText(null);
                        if (pinnedFile != null) {
                            break;
                        }
                    }
                }
                if (!found) {
                    issues.add("lockfile pins artifact '" + pinnedFile
                            + "' which is not present in the bundle manifest");
                } else if (matchSha == null
                        || !matchSha.equalsIgnoreCase(pinned.artifactSha256())) {
                    issues.add("lockfile sha256 pin '" + pinned.artifactSha256()
                            + "' does not match the bundle manifest sha '"
                            + (matchSha == null ? "<missing>" : matchSha)
                            + "' for artifact '" + pinnedFile + "'");
                }
            }
        } catch (Exception malformed) {
            issues.add("bundle manifest is not readable: " + malformed.getMessage());
        }
        if (!issues.isEmpty()) {
            return new Result(false, issues);
        }
        List<String> ok = new ArrayList<String>();
        ok.add("lockfile pins match the bundle manifest (model/revision/sha256)");
        return new Result(true, ok);
    }

    /**
     * Verifies an air-gap bundle before deployment (spec 62.3/94).
     */
    public static Result checkBundle(File bundle) {
        List<String> issues = new ArrayList<String>();
        if (bundle == null || !bundle.isDirectory()) {
            issues.add("bundle directory not found: "
                    + (bundle == null ? "null" : bundle.getPath()));
            return new Result(false, issues);
        }
        io.cortexj.repository.local.bundle.ModelBundler bundler =
                new io.cortexj.repository.local.bundle.ModelBundler(
                        new ArrayList<io.cortexj.spi.repository.ModelRepositoryProvider>(), null);
        io.cortexj.repository.local.bundle.BundleReport report =
                bundler.verify(bundle.toPath());
        if (!report.isOk()) {
            issues.addAll(report.issues());
            return new Result(false, issues);
        }
        List<String> ok = new ArrayList<String>();
        ok.add("bundle verified: " + report.modelId() + " (" + report.verifiedArtifacts()
                + " artifacts)");
        return new Result(true, ok);
    }

    /**
     * Checks runtime health and (optionally) a required runtime, plus model
     * plannability when a model reference is given (spec 94: "native runtime
     * availability").
     */
    public static Result checkRuntimes(String model, String requiredRuntime) {
        List<String> messages = new ArrayList<String>();
        List<String> issues = new ArrayList<String>();
        io.cortexj.CortexJ platform = io.cortexj.CortexJ.builder().build();
        try {
            for (io.cortexj.runtime.RuntimeHealthReport health : platform.runtimeHealth()) {
                messages.add(health.runtimeId() + ": " + health.health());
                if (requiredRuntime != null
                        && requiredRuntime.equals(health.runtimeId().value())
                        && health.health() != io.cortexj.runtime.RuntimeHealth.AVAILABLE) {
                    issues.add("required runtime '" + requiredRuntime
                            + "' is not available on this machine");
                }
            }
            if (model != null && !model.trim().isEmpty()) {
                try {
                    io.cortexj.diagnostics.SupportReport support = platform.support()
                            .inspect(io.cortexj.planner.ExecutionRequest.forModel(
                                    io.cortexj.model.ModelReference.of(model)));
                    if (support.viableCandidates().isEmpty()) {
                        issues.add("model '" + model + "' has no viable execution path on this machine");
                    } else {
                        messages.add("model '" + model + "' is plannable via "
                                + support.viableCandidates().get(0).runtimeId());
                    }
                } catch (RuntimeException unplannable) {
                    issues.add("model '" + model + "' cannot be planned: "
                            + unplannable.getMessage());
                }
            }
        } finally {
            platform.close();
        }
        if (!issues.isEmpty()) {
            return new Result(false, issues);
        }
        return new Result(true, messages);
    }

    private static int starterLineOf(String starterArtifact) {
        String digits = starterArtifact.replace("cortexj-starter-java", "");
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException invalid) {
            return -1;
        }
    }
}
