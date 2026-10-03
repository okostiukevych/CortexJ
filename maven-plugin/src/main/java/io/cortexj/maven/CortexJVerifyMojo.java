package io.cortexj.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * cortexj:verify — build-time deployment verification (spec 93/94):
 * correct starter, native runtime availability, pinned model bundle,
 * checksum manifest/lockfile.
 */
@Mojo(name = "verify", defaultPhase = LifecyclePhase.VALIDATE)
public class CortexJVerifyMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject project;

    @Parameter(property = "cortexj.lockfile")
    private File lockfile;

    @Parameter(property = "cortexj.bundle")
    private File bundle;

    @Parameter(property = "cortexj.model")
    private String model;

    @Parameter(property = "cortexj.requiredRuntime")
    private String requiredRuntime;

    @Parameter(property = "cortexj.skip")
    private boolean skip;

    @Override
    public void execute() throws MojoFailureException {
        if (skip) {
            getLog().info("cortexj:verify skipped");
            return;
        }
        List<String> failures = new ArrayList<String>();

        String target = compilerTarget();
        List<String> dependencyKeys = new ArrayList<String>();
        for (Object dependency : project.getDependencies()) {
            org.apache.maven.model.Dependency mavenDependency =
                    (org.apache.maven.model.Dependency) dependency;
            dependencyKeys.add(mavenDependency.getGroupId() + ":" + mavenDependency.getArtifactId());
        }
        DeploymentVerifier.Result starter = DeploymentVerifier.checkStarter(dependencyKeys, target);
        log(starter, failures);

        if (lockfile != null) {
            log(DeploymentVerifier.checkLockfile(lockfile), failures);
        }
        if (bundle != null) {
            log(DeploymentVerifier.checkBundle(bundle), failures);
        }
        if (lockfile != null && bundle != null) {
            log(DeploymentVerifier.checkLockfileAgainstBundle(lockfile, bundle), failures);
        }
        if (model != null || requiredRuntime != null) {
            log(DeploymentVerifier.checkRuntimes(model, requiredRuntime), failures);
        }

        if (!failures.isEmpty()) {
            for (String failure : failures) {
                getLog().error("cortexj: " + failure);
            }
            throw new MojoFailureException("CortexJ deployment verification failed with "
                    + failures.size() + " issue(s)");
        }
        getLog().info("cortexj:verify passed");
    }

    private void log(DeploymentVerifier.Result result, List<String> failures) {
        for (String message : result.messages) {
            if (result.ok) {
                getLog().info("cortexj: " + message);
            } else {
                getLog().warn("cortexj: " + message);
            }
        }
        if (!result.ok) {
            failures.addAll(result.messages);
        }
    }

    private String compilerTarget() {
        String release = project.getProperties().getProperty("maven.compiler.release");
        if (release != null && !release.trim().isEmpty()) {
            return release.trim();
        }
        String target = project.getProperties().getProperty("maven.compiler.target");
        return target == null || target.trim().isEmpty() ? "8" : target.trim();
    }
}
