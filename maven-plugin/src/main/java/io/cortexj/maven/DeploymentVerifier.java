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
