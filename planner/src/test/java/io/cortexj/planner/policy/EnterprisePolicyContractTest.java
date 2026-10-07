package io.cortexj.planner.policy;

import io.cortexj.spi.policy.ExecutionPolicy;
import io.cortexj.tck.ExecutionPolicyContract;

/**
 * Audit 2026-10-07 (#148): the planner-policy contract, exercised.
 */
class EnterprisePolicyContractTest extends ExecutionPolicyContract {

    @Override
    protected ExecutionPolicy policy() {
        return EnterpriseRuntimePolicy.unrestricted();
    }
}
