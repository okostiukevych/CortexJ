package io.cortexj.train.multinode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Distributed topology planner (ADR-027): plans worker/shard layouts
 * for data-parallel training. Lives here — never inside the inference
 * planner — and reuses only capability/device vocabulary, never
 * inference decisions.
 */
public final class TopologyPlanner {

    /**
     * One planned worker: identity, endpoint and its row range.
     */
    public static final class WorkerPlan {

        private final String id;
        private final String endpoint;
        private final int fromRow;
        private final int toRow;

        public WorkerPlan(String id, String endpoint, int fromRow, int toRow) {
            if (id == null || endpoint == null || fromRow < 0 || toRow < fromRow) {
                throw new IllegalArgumentException("invalid worker plan");
            }
            this.id = id;
            this.endpoint = endpoint;
            this.fromRow = fromRow;
            this.toRow = toRow;
        }

        public String id() {
            return id;
        }

        public String endpoint() {
            return endpoint;
        }

        public int fromRow() {
            return fromRow;
        }

        public int toRow() {
            return toRow;
        }

        public int rows() {
            return toRow - fromRow;
        }
    }

    /**
     * Plans a sharding: rows split evenly across the workers, the last
     * worker takes the remainder. Deterministic for the same inputs.
     */
    public static List<WorkerPlan> plan(List<String> workerEndpoints, int rows) {
        if (workerEndpoints == null || workerEndpoints.isEmpty()) {
            throw new IllegalArgumentException("at least one worker endpoint is required");
        }
        if (rows < workerEndpoints.size()) {
            throw new IllegalArgumentException("batch must have at least one row per worker");
        }
        List<WorkerPlan> plans = new ArrayList<>(workerEndpoints.size());
        int base = rows / workerEndpoints.size();
        for (int w = 0; w < workerEndpoints.size(); w++) {
            int from = w * base;
            int to = w == workerEndpoints.size() - 1 ? rows : from + base;
            plans.add(new WorkerPlan("worker-" + w, workerEndpoints.get(w), from, to));
        }
        return Collections.unmodifiableList(plans);
    }
}
