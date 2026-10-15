package io.cortexj.cli;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Architecture guard (ADR-016): the Runtime foundation must never depend
 * on future product tracks (Lab / Data / Experiments / Train / Tensor /
 * Autograd / Research), and core must stay free of framework and engine
 * dependencies (AP-001/AP-002).
 *
 * <p>Scope follows the ADR-016 module graph, not the whole codebase:
 * future tracks may use each other, and leaf tools (the CLI) may use
 * any track - the production inference path stays standalone because
 * the foundation jars never gain those dependencies (G-016).</p>
 */
class ArchitectureGuardTest {

    private static final JavaClasses RUNTIME =
            new ClassFileImporter().importPackages("io.cortexj");

    /** Future-track packages plus leaf consumers: outside the foundation scope. */
    private static final String[] OUTSIDE_FOUNDATION = {
            "io.cortexj.cli..",
            "io.cortexj.lab..",
            "io.cortexj.data..",
            "io.cortexj.experiments..",
            "io.cortexj.train..",
            "io.cortexj.tensor..",
            "io.cortexj.autograd..",
            "io.cortexj.research..",
            "io.cortexj.notebook..",
            "io.cortexj.nn..",
    };

    @Test
    void runtimeNeverDependsOnFutureTracks() {
        noClasses()
                .that().resideInAnyPackage(
                        "io.cortexj..")
                .and().resideOutsideOfPackages(OUTSIDE_FOUNDATION)
                .should().dependOnClassesThat().resideInAnyPackage(
                        "io.cortexj.lab..",
                        "io.cortexj.data..",
                        "io.cortexj.experiments..",
                        "io.cortexj.train..",
                        "io.cortexj.tensor..",
                        "io.cortexj.autograd..",
                        "io.cortexj.research..",
                        "io.cortexj.notebook..",
                        "io.cortexj.nn..")
                .because("ADR-016: runtime modules MUST NOT depend on future tracks; "
                        + "the production path stays standalone")
                .check(RUNTIME);
    }

    @Test
    void experimentalApisDoNotLeakIntoStableRuntime() {
        // ADR-016: incubating Lab/Research APIs live under
        // io.cortexj.experimental and never in stable Runtime signatures.
        // Vacuous until the first experimental package exists — then it
        // guards the stabilization boundary.
        noClasses()
                .that().resideInAnyPackage(
                        "io.cortexj..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "io.cortexj.experimental..")
                .because("ADR-016: experimental APIs must not leak into stable Runtime")
                .check(RUNTIME);
    }

    @Test
    void coreHasNoFrameworkOrEngineDependencies() {
        long violations = RUNTIME.stream()
                .filter(clazz -> clazz.getPackageName().startsWith("io.cortexj.core"))
                .flatMap(clazz -> clazz.getDirectDependenciesFromSelf().stream())
                .map(dep -> dep.getTargetClass().getPackageName())
                .filter(target -> target.startsWith("org.springframework")
                        || target.startsWith("ai.djl")
                        || target.startsWith("com.microsoft.onnxruntime")
                        || target.startsWith("dev.langchain4j"))
                .count();
        assertTrue(violations == 0,
                "AP-001/AP-002: cortexj-core must not reference frameworks or engines, found "
                        + violations + " references");
    }
}
