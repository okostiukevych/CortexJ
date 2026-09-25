package io.cortexj.cli;

import io.cortexj.CortexJ;
import io.cortexj.diagnostics.Recommendation;
import io.cortexj.diagnostics.SupportReport;
import io.cortexj.error.CortexJException;
import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelReference;
import io.cortexj.planner.ExecutionRequest;
import io.cortexj.planner.FallbackPolicy;
import io.cortexj.runtime.RuntimeDescriptor;

import java.util.List;

public final class CortexJMain {

    public static void main(String[] args) {
        try {
            int status = run(args);
            System.exit(status);
        } catch (CortexJException e) {
            System.err.println(e.toString());
            System.exit(2);
        }
    }

    static int run(String[] args) {
        if (args.length == 0) {
            usage();
            return 1;
        }
        String command = args[0];
        if (command.equals("help") || command.equals("--help") || command.equals("-h")) {
            usage();
            return 0;
        }

        CortexJ platform = CortexJ.builder().build();
        try {
            if (command.equals("doctor")) {
                return doctor(platform);
            }
            if (command.equals("runtimes")) {
                return runtimes(platform);
            }
            if (command.equals("hardware")) {
                return hardware(platform);
            }
            if (command.equals("inspect")) {
                return inspect(platform, args);
            }
            if (command.equals("plan")) {
                return plan(platform, args);
            }
            if (command.equals("pull")) {
                return pull(platform, args);
            }
            if (command.equals("run")) {
                return runModel(platform, args);
            }
            if (command.equals("bundle")) {
                return bundle(args);
            }
            if (command.equals("cache")) {
                return cache(args);
            }
            System.err.println("Unknown command: " + command);
            usage();
            return 1;
        } finally {
            platform.close();
        }
    }

    private static void usage() {
        System.out.println("cortexj - AI runtime orchestration for the JVM");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  cortexj doctor                       show environment, runtimes and recommendations");
        System.out.println("  cortexj runtimes                     list registered runtime providers");
        System.out.println("  cortexj hardware                     show detected hardware profile");
        System.out.println("  cortexj inspect <model>              can this model run here, and what is needed");
        System.out.println("  cortexj plan <model> [options]        print the explainable execution plan");
        System.out.println("  cortexj pull <model>                 download the planned artifacts into the cache");
        System.out.println("  cortexj run <model> --prompt <text>   load and execute the model");
        System.out.println();
        System.out.println("Cache management:");
        System.out.println("  cortexj cache list                    list cached artifacts");
        System.out.println("  cortexj cache clean                   remove all cached artifacts");
        System.out.println();
        System.out.println("Air-gap bundle:");
        System.out.println("  cortexj bundle create <model> --out <dir>   create a portable offline bundle");
        System.out.println("  cortexj bundle verify <dir>                 verify a bundle before deployment");
        System.out.println();
        System.out.println("Options for plan/pull/run:");
        System.out.println("  --runtime <id>        require a specific runtime");
        System.out.println("  --prefer-runtime <id> prefer a runtime");
        System.out.println("  --offline             forbid network access");
        System.out.println("  --limit-mb <n>        hard memory limit in MiB");
        System.out.println("  --prompt <text>       prompt for 'run'");
        System.out.println("  --lockfile <path>     enforce a strict pinned plan (plan mode)");
        System.out.println("  --lockfile-out <path> write the lockfile for the produced plan");
    }

    private static int doctor(CortexJ platform) {
        System.out.println("== CortexJ doctor ==");
        System.out.println();
        System.out.println("Environment:");
        System.out.println("  " + platform.environment());
        System.out.println();
        System.out.println("Detected platform module:");
        System.out.println("  " + platform.platformModule());
        System.out.println();
        System.out.println("Detected starters:");
        java.util.List<io.cortexj.StarterInfo> starters = new java.util.ArrayList<io.cortexj.StarterInfo>();
        try {
            for (io.cortexj.StarterInfo starter : java.util.ServiceLoader.load(io.cortexj.StarterInfo.class)) {
                starters.add(starter);
            }
        } catch (Throwable ignored) {
        }
        if (starters.isEmpty()) {
            System.out.println("  (none on classpath)");
        }
        for (io.cortexj.StarterInfo starter : starters) {
            System.out.println("  - " + starter.starterId() + " (requires Java " + starter.requiredFeatureVersion() + ")");
        }
        System.out.println();
        System.out.println("Hardware:");
        System.out.println("  " + platform.hardware());
        System.out.println();
        System.out.println("Runtimes (probe results):");
        List<io.cortexj.runtime.RuntimeHealthReport> health = platform.runtimeHealth();
        if (health.isEmpty()) {
            System.out.println("  (none registered - add a cortexj starter or runtime artifact)");
        }
        for (io.cortexj.runtime.RuntimeHealthReport report : health) {
            System.out.println("  - " + report.runtimeId() + ": " + report.health().name().toLowerCase()
                    + (report.message().isEmpty() ? "" : " - " + report.message()));
        }
        System.out.println();
        System.out.println("Recommendation:");
        System.out.println("  use 'cortexj inspect <model>' to check a specific model");
        return 0;
    }

    private static String[] format(RuntimeDescriptor descriptor) {
        String[] parts = new String[]{
                descriptor.maturity().name(),
                descriptor.isNativeRuntime() ? "native" : "pure-java",
                "min-java-" + descriptor.minJavaFeatureVersion()
        };
        return parts;
    }

    private static int runtimes(CortexJ platform) {
        for (RuntimeDescriptor descriptor : platform.runtimes()) {
            System.out.println(descriptor.id() + "\t" + descriptor.version()
                    + "\ttasks=" + descriptor.tasks()
                    + "\tformats=" + descriptor.formats()
                    + "\tdevices=" + descriptor.devices());
        }
        return 0;
    }

    private static int hardware(CortexJ platform) {
        System.out.println(platform.hardware());
        for (String note : platform.hardware().detectionNotes()) {
            System.out.println("  note: " + note);
        }
        return 0;
    }

    private static int inspect(CortexJ platform, String[] args) {
        String model = modelArg(args);
        SupportReport report = platform.support().inspect(ModelReference.of(model));
        System.out.println(report.explanation());
        for (Recommendation recommendation : report.recommendations()) {
            System.out.println("Recommendation: " + recommendation);
        }
        return report.isExecutable() ? 0 : 3;
    }

    private static int plan(CortexJ platform, String[] args) {
        String model = modelArg(args);
        ExecutionRequest request = buildRequest(args, model);
        io.cortexj.planner.ExecutionPlan plan = platform.planner().plan(request);
        System.out.print(plan.explanation());
        String lockfileOut = optionValue(args, "--lockfile-out");
        if (lockfileOut != null) {
            try {
                io.cortexj.planner.ModelLockfile.fromPlan(plan).writeTo(java.nio.file.Paths.get(lockfileOut));
                System.out.println();
                System.out.println("lockfile written: " + lockfileOut);
            } catch (java.io.IOException e) {
                System.err.println("cannot write lockfile: " + e.getMessage());
                return 2;
            }
        }
        return 0;
    }

    private static int pull(CortexJ platform, String[] args) {
        String model = modelArg(args);
        ExecutionRequest request = buildRequest(args, model);
        SupportReport report = platform.support().inspect(request);
        for (io.cortexj.planner.PlanDownload download : report.requiredDownloads()) {
            System.out.println("planned download: " + download);
        }
        if (report.requiredDownloads().isEmpty()) {
            System.out.println("nothing to download: all viable artifacts are already available locally");
            return 0;
        }
        try {
            ModelHandle handle = platform.models().load(request);
            handle.close();
            System.out.println("pull completed");
            return 0;
        } catch (CortexJException e) {
            SupportReport after = platform.support().inspect(request);
            if (after.requiredDownloads().isEmpty() && report.requiredDownloads().size() > 0) {
                System.out.println("pull completed (artifacts cached; execution was not attempted: "
                        + e.code() + " " + e.getMessage() + ")");
                return 0;
            }
            System.err.println("pull failed:");
            System.err.println(e.toString());
            return 2;
        }
    }

    private static int runModel(CortexJ platform, String[] args) {
        String model = modelArg(args);
        String prompt = optionValue(args, "--prompt");
        if (prompt == null) {
            System.err.println("run requires --prompt <text>");
            return 1;
        }
        ExecutionRequest request = buildRequest(args, model);
        ModelHandle handle = platform.models().load(request);
        try {
            io.cortexj.inference.TextGenerationCapability generator =
                    handle.capability(io.cortexj.inference.TextGenerationCapability.class);
            io.cortexj.inference.GenerationResponse response = generator.generate(
                    io.cortexj.inference.GenerationRequest.prompt(prompt));
            System.out.println(response.text());
            return 0;
        } finally {
            handle.close();
        }
    }

    private static int cache(String[] args) {
        String action = args.length >= 2 ? args[1] : "";
        io.cortexj.cache.CacheStorage storage = defaultCache();
        if (action.equals("list")) {
            try {
                java.util.List<io.cortexj.cache.CacheEntry> entries = storage.list();
                if (entries.isEmpty()) {
                    System.out.println("cache is empty: " + storage.root());
                    return 0;
                }
                long total = 0L;
                for (io.cortexj.cache.CacheEntry entry : entries) {
                    total += entry.sizeBytes();
                    System.out.printf(java.util.Locale.ROOT, "%10.1f MiB  %s%n",
                            Double.valueOf(entry.sizeBytes() / 1048576.0), entry.key());
                }
                System.out.printf(java.util.Locale.ROOT, "%10.1f MiB total (%d artifacts)%n",
                        Double.valueOf(total / 1048576.0), Integer.valueOf(entries.size()));
                return 0;
            } catch (java.io.IOException e) {
                System.err.println("cache listing failed: " + e.getMessage());
                return 2;
            }
        }
        if (action.equals("clean")) {
            try {
                long freed = storage.clear();
                System.out.printf(java.util.Locale.ROOT,
                        "cache cleaned, freed %.1f MiB at %s%n",
                        Double.valueOf(freed / 1048576.0), storage.root());
                return 0;
            } catch (java.io.IOException e) {
                System.err.println("cache cleanup failed: " + e.getMessage());
                return 2;
            }
        }
        System.err.println("Unknown cache action: " + action + " (use list or clean)");
        return 1;
    }

    private static int bundle(String[] args) {
        if (args.length < 3) {
            usage();
            return 1;
        }
        String action = args[1];
        java.util.List<io.cortexj.spi.repository.ModelRepositoryProvider> repositories =
                io.cortexj.core.SpiRegistries.loadSorted(io.cortexj.spi.repository.ModelRepositoryProvider.class,
                        new io.cortexj.core.SpiRegistries.IdAccessor<io.cortexj.spi.repository.ModelRepositoryProvider>() {
                            public String idOf(io.cortexj.spi.repository.ModelRepositoryProvider provider) {
                                return provider.id().value();
                            }
                        }, java.util.Collections.<io.cortexj.spi.repository.ModelRepositoryProvider>emptyList());
        io.cortexj.repository.local.bundle.ModelBundler bundler =
                new io.cortexj.repository.local.bundle.ModelBundler(repositories, defaultCache());
        if (action.equals("create")) {
            String model = args[2];
            String out = optionValue(args, "--out");
            if (out == null) {
                System.err.println("bundle create requires --out <dir>");
                return 1;
            }
            boolean offline = false;
            for (String arg : args) {
                if (arg.equals("--offline")) {
                    offline = true;
                    break;
                }
            }
            io.cortexj.spi.repository.ResolutionContext context =
                    io.cortexj.spi.repository.ResolutionContext.create(offline,
                            io.cortexj.spi.repository.TokenSupplier.none(), null, null);
            java.nio.file.Path bundleDir =
                    bundler.create(io.cortexj.model.ModelReference.of(model), java.nio.file.Paths.get(out), context);
            io.cortexj.repository.local.bundle.BundleReport report = bundler.verify(bundleDir);
            System.out.println("bundle created: " + bundleDir);
            System.out.println("  model:    " + report.modelId());
            System.out.println("  revision: " + report.revision());
            System.out.println("  artifacts verified: " + report.verifiedArtifacts());
            return report.isOk() ? 0 : 2;
        }
        if (action.equals("verify")) {
            io.cortexj.repository.local.bundle.BundleReport report =
                    bundler.verify(java.nio.file.Paths.get(args[2]));
            System.out.println("bundle: " + args[2]);
            if (report.modelId() != null) {
                System.out.println("  model:    " + report.modelId());
                System.out.println("  revision: " + report.revision());
                System.out.println("  artifacts verified: " + report.verifiedArtifacts());
            }
            for (String issue : report.issues()) {
                System.out.println("  ERROR: " + issue);
            }
            System.out.println(report.isOk() ? "bundle is consistent" : "bundle verification FAILED");
            return report.isOk() ? 0 : 2;
        }
        System.err.println("Unknown bundle action: " + action + " (use create or verify)");
        return 1;
    }

    private static io.cortexj.cache.CacheStorage defaultCache() {
        String home = System.getProperty("user.home", ".");
        return new io.cortexj.cache.CacheStorage(java.nio.file.Paths.get(home, ".cortexj", "cache"));
    }

    private static String modelArg(String[] args) {
        if (args.length < 2) {
            throw new IllegalArgumentException("command requires a model argument, e.g. cortexj plan Qwen/Qwen3-8B");
        }
        return args[1];
    }

    private static ExecutionRequest buildRequest(String[] args, String model) {
        ExecutionRequest.Builder builder = ExecutionRequest.builder(ModelReference.of(model));
        String required = optionValue(args, "--runtime");
        if (required != null) {
            builder.requiredRuntime(required);
            builder.fallbackPolicy(FallbackPolicy.DISABLED);
        }
        String preferred = optionValue(args, "--prefer-runtime");
        if (preferred != null) {
            builder.preferredRuntime(preferred);
        }
        String limit = optionValue(args, "--limit-mb");
        if (limit != null) {
            builder.memoryLimitBytes(Long.parseLong(limit) * 1024L * 1024L);
        }
        for (String arg : args) {
            if (arg.equals("--offline")) {
                builder.offline(true);
                break;
            }
        }
        String lockfilePath = optionValue(args, "--lockfile");
        if (lockfilePath != null) {
            try {
                builder.lockfile(io.cortexj.planner.ModelLockfile.readFrom(java.nio.file.Paths.get(lockfilePath)))
                        .reproducibilityMode(io.cortexj.planner.ReproducibilityMode.PINNED_PLAN);
            } catch (java.io.IOException e) {
                throw new IllegalArgumentException("cannot read lockfile " + lockfilePath + ": " + e.getMessage());
            }
        }
        return builder.build();
    }

    private static String optionValue(String[] args, String option) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals(option) && i + 1 < args.length) {
                return args[i + 1];
            }
        }
        return null;
    }
}
