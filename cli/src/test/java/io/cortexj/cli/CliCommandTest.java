package io.cortexj.cli;

import io.cortexj.CortexJ;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliCommandTest {

    @Test
    void doctorCommandRunsOnCurrentJvm() {
        int status = CortexJMain.run(new String[]{"doctor"});
        assertEquals(0, status);
    }

    @Test
    void runtimesCommandPrintsRegisteredProviders() {
        int status = CortexJMain.run(new String[]{"runtimes"});
        assertEquals(0, status);
    }

    @Test
    void hardwareCommandPrintsProfile() {
        int status = CortexJMain.run(new String[]{"hardware"});
        assertEquals(0, status);
    }

    @Test
    void unknownCommandFails() {
        assertEquals(1, CortexJMain.run(new String[]{"definitely-not-a-command"}));
    }

    @Test
    void inspectUnknownModelReportsGracefully() {
        int status = CortexJMain.run(new String[]{"inspect", "no-such-owner/no-such-model-xyz"});
        assertTrue(status == 0 || status == 3);
    }
}
