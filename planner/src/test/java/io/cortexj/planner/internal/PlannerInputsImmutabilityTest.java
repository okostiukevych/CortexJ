package io.cortexj.planner.internal;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

class PlannerInputsImmutabilityTest {

    @Test
    void immutableListsThrowOnMutation() {
        PlannerInputs inputs = new PlannerInputs(
                new ArrayList<io.cortexj.spi.runtime.RuntimeProvider>(),
                new ArrayList<io.cortexj.spi.repository.ModelRepositoryProvider>(),
                new ArrayList<io.cortexj.spi.conversion.ModelConverter>(),
                new ArrayList<io.cortexj.spi.policy.ExecutionPolicy>(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                false,
                "test",
                0L);

        assertThrows(UnsupportedOperationException.class,
                () -> inputs.runtimeProviders.add(null),
                "runtimeProviders must be unmodifiable");
        assertThrows(UnsupportedOperationException.class,
                () -> inputs.repositoryProviders.add(null),
                "repositoryProviders must be unmodifiable");
        assertThrows(UnsupportedOperationException.class,
                () -> inputs.converters.add(null),
                "converters must be unmodifiable");
        assertThrows(UnsupportedOperationException.class,
                () -> inputs.policies.add(null),
                "policies must be unmodifiable");
    }
}
