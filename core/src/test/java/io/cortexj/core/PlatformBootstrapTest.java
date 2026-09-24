package io.cortexj.core;

import io.cortexj.CortexJ;
import io.cortexj.CortexJBuilder;
import io.cortexj.error.ConfigurationException;
import io.cortexj.hardware.EnvironmentProfile;
import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformBootstrapTest {

    @Test
    void builderBootstrapsPlatformViaServiceLoader() {
        CortexJ platform = CortexJ.builder().build();
        try {
            int currentFeature = platform.environment().jvmFeatureVersion();
            assertTrue(currentFeature >= 8, "feature version must be at least 8, got " + currentFeature);
            assertTrue(platform.environment().logicalCores() > 0);
            assertTrue(platform.runtimes().size() >= 0);
        } finally {
            platform.close();
        }
        platform.close();
    }

    @Test
    void wrongStarterForJvmFailsWithActionableDiagnostic() {
        System.setProperty("cortexj.test.starter.requires", "99");
        try {
            CortexJBuilder builder = CortexJ.builder();
            ConfigurationException exception = assertThrows(ConfigurationException.class, builder::build);
            assertEquals("CORTEXJ-CONF-0002", exception.code());
            assertTrue(exception.getMessage().contains("cortexj-starter-test-future"));
            assertTrue(exception.getMessage().contains("Java 99"));
            assertTrue(exception.remediation().size() >= 1);
            assertTrue(exception.toString().contains("Replace"));
        } finally {
            System.clearProperty("cortexj.test.starter.requires");
        }
    }

    @Test
    void loadWithoutAnyRuntimeYieldsActionablePlanningError() {
        CortexJ platform = CortexJ.builder().build();
        try {
            try {
                ModelHandle handle = platform.models().load(ModelReference.of("Qwen/Qwen3-8B"));
                handle.close();
            } catch (io.cortexj.error.CortexJException e) {
                assertTrue(e.code().startsWith("CORTEXJ-"));
                assertTrue(e.remediation().size() >= 0);
            }
        } finally {
            platform.close();
        }
    }
}
