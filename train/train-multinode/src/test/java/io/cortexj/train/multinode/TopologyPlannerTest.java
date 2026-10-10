package io.cortexj.train.multinode;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TopologyPlannerTest {

    @Test
    void shardingIsEvenAndLossless() {
        List<TopologyPlanner.WorkerPlan> plans = TopologyPlanner.plan(
                Arrays.asList("tcp://a:1", "tcp://b:2", "tcp://c:3"), 10);
        assertEquals(3, plans.size());
        assertEquals(0, plans.get(0).fromRow());
        assertEquals(3, plans.get(0).toRow());
        assertEquals(3, plans.get(1).fromRow());
        // the last worker takes the remainder: 10 = 3 + 3 + 4
        assertEquals(6, plans.get(2).fromRow());
        assertEquals(10, plans.get(2).toRow());
        assertEquals("worker-0", plans.get(0).id());
        assertEquals("tcp://b:2", plans.get(1).endpoint());
    }

    @Test
    void shardingIsDeterministic() {
        List<String> endpoints = Arrays.asList("tcp://a:1", "tcp://b:2");
        List<TopologyPlanner.WorkerPlan> first = TopologyPlanner.plan(endpoints, 8);
        List<TopologyPlanner.WorkerPlan> second = TopologyPlanner.plan(endpoints, 8);
        for (int i = 0; i < 2; i++) {
            assertEquals(first.get(i).fromRow(), second.get(i).fromRow());
            assertEquals(first.get(i).toRow(), second.get(i).toRow());
        }
    }

    @Test
    void emptyWorkersOrFewRowsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> TopologyPlanner.plan(Arrays.asList(), 8));
        assertThrows(IllegalArgumentException.class,
                () -> TopologyPlanner.plan(Arrays.asList("a", "b", "c"), 2));
    }
}
