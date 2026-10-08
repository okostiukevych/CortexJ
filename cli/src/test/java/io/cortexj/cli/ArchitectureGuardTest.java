package io.cortexj.cli;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec v3.0 §125.1-125.4, §134.2 (issue #157): the Runtime foundation must
 * never depend on future product tracks (Lab / Data / Experiments / Train
 * / Tensor / Autograd / Research), and core must stay free of framework
 * and engine dependencies (AP-001/AP-002).
 *
 * <p>The future-track packages do not exist yet, so the first rule passes
 * vacuously today - its value is failing the build the moment someone
 * wires a future track into the Runtime foundation.</p>
 */
class ArchitectureGuardTest {

    private static final JavaClasses RUNTIME =
            new ClassFileImporter().importPackages("io.cortexj");

    @Test
    void runtimeNeverDependsOnFutureTracks() {
        noClasses()
                .that().resideInAnyPackage(
                        "io.cortexj..")
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
                .because("spec v3.0 §125: runtime modules MUST NOT depend on future tracks; "
                        + "the production path stays standalone")
                .check(RUNTIME);
    }

    @Test
    void experimentalApisDoNotLeakIntoStableRuntime() {
        // spec v3.0 §150 (ADR-016): incubating Lab/Research APIs live under
        // io.cortexj.experimental and never in stable Runtime signatures.
        // Vacuous until the first experimental package exists — then it
        // guards the stabilization boundary.
        noClasses()
                .that().resideInAnyPackage(
                        "io.cortexj..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "io.cortexj.experimental..")
                .because("spec v3.0 §150: experimental APIs must not leak into stable Runtime")
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
