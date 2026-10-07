package io.cortexj.tck;

import io.cortexj.model.ModelReference;
import io.cortexj.planner.ExecutionCandidate;
import io.cortexj.planner.ExecutionRequest;
import io.cortexj.spi.policy.ExecutionPolicy;
import io.cortexj.spi.policy.PlanningContext;
import io.cortexj.spi.policy.PolicyDecision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract for {@link ExecutionPolicy} implementations (audit 2026-10-07,
 * #148): planner policies previously had no behavioral contract.
 */
public abstract class ExecutionPolicyContract {

    protected abstract ExecutionPolicy policy();

    protected PlanningContext planningContext() {
        return new PlanningContext(null, null,
                ExecutionRequest.forModel(ModelReference.of("tck/model")),
                new PlanningContext.CacheLookup() {
                    @Override
                    public boolean isCached(String repositoryId, String revision,
                                            String artifactId, String checksum) {
                        return false;
                    }

                    @Override
                    public java.nio.file.Path cachedArtifactPath(String repositoryId,
                                                                 String revision, String artifactId,
                                                                 String checksum) {
                        return null;
                    }
                });
    }

    protected ExecutionCandidate candidate() {
        return ExecutionCandidate.builder("tck-runtime").build();
    }

    @Test
    public void identityIsPresent() {
        assertNotNull(policy().policyId(), "policy id must not be null");
        assertTrue(!policy().policyId().trim().isEmpty(), "policy id must not be blank");
    }

    @Test
    public void evaluationIsTotalAndDeterministic() {
        PolicyDecision first = policy().evaluate(candidate(), planningContext());
        assertNotNull(first, "evaluate must return a decision, never null");
        PolicyDecision second = policy().evaluate(candidate(), planningContext());
        assertEquals(first.outcome(), second.outcome(),
                "evaluate must be deterministic for the same candidate");
        assertEquals(first.scoreAdjustment(), second.scoreAdjustment(), 0.0d);
    }

    @Test
    public void rejectionCarriesAReason() {
        PolicyDecision decision = policy().evaluate(candidate(), planningContext());
        if (decision.isRejection()) {
            assertNotNull(decision.reasonCode(), "a rejection must carry a reason code");
        }
    }
}
