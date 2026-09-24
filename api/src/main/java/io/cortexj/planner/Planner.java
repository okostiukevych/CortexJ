package io.cortexj.planner;

public interface Planner {

    ExecutionPlan plan(ExecutionRequest request);
}
