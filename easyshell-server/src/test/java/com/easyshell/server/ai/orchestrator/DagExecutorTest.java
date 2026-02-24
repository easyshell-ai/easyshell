package com.easyshell.server.ai.orchestrator;

import com.easyshell.server.ai.config.AgenticConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DagExecutor — hasCycle detection (Kahn's algorithm)")
class DagExecutorTest {

    @Mock
    private AgenticConfigService configService;

    private DagExecutor dagExecutor;

    @BeforeEach
    void setUp() {
        when(configService.getInt(eq("ai.dag.max-concurrent-steps"), anyInt())).thenReturn(2);
        dagExecutor = new DagExecutor(null, null, null, null, null, configService, null);
    }

    private ExecutionPlan.PlanStep step(int index, List<Integer> dependsOn) {
        return ExecutionPlan.PlanStep.builder()
                .index(index)
                .dependsOn(dependsOn)
                .description("step " + index)
                .build();
    }

    @Test
    void noCycle_linearChain() {
        // 0 → 1 → 2
        List<ExecutionPlan.PlanStep> steps = List.of(
                step(0, null),
                step(1, List.of(0)),
                step(2, List.of(1))
        );
        assertThat(dagExecutor.hasCycle(steps)).isFalse();
    }

    @Test
    void noCycle_parallelSteps() {
        // 0, 1 (independent) → 2
        List<ExecutionPlan.PlanStep> steps = List.of(
                step(0, null),
                step(1, null),
                step(2, List.of(0, 1))
        );
        assertThat(dagExecutor.hasCycle(steps)).isFalse();
    }

    @Test
    void noCycle_singleStep() {
        List<ExecutionPlan.PlanStep> steps = List.of(step(0, null));
        assertThat(dagExecutor.hasCycle(steps)).isFalse();
    }

    @Test
    void noCycle_emptySteps() {
        assertThat(dagExecutor.hasCycle(new ArrayList<>())).isFalse();
    }

    @Test
    void cycle_selfLoop() {
        List<ExecutionPlan.PlanStep> steps = List.of(
                step(0, List.of(0))  // self-loop
        );
        assertThat(dagExecutor.hasCycle(steps)).isTrue();
    }

    @Test
    void cycle_twoNodes() {
        // 0 → 1 → 0
        List<ExecutionPlan.PlanStep> steps = List.of(
                step(0, List.of(1)),
                step(1, List.of(0))
        );
        assertThat(dagExecutor.hasCycle(steps)).isTrue();
    }

    @Test
    void cycle_threeNodeTriangle() {
        // 0 → 1 → 2 → 0
        List<ExecutionPlan.PlanStep> steps = List.of(
                step(0, List.of(2)),
                step(1, List.of(0)),
                step(2, List.of(1))
        );
        assertThat(dagExecutor.hasCycle(steps)).isTrue();
    }

    @Test
    void noCycle_diamondShape() {
        //   0
        //  / \
        // 1   2
        //  \ /
        //   3
        List<ExecutionPlan.PlanStep> steps = List.of(
                step(0, null),
                step(1, List.of(0)),
                step(2, List.of(0)),
                step(3, List.of(1, 2))
        );
        assertThat(dagExecutor.hasCycle(steps)).isFalse();
    }

    @Test
    void noCycle_allIndependent() {
        List<ExecutionPlan.PlanStep> steps = List.of(
                step(0, null),
                step(1, null),
                step(2, null)
        );
        assertThat(dagExecutor.hasCycle(steps)).isFalse();
    }

    @Test
    void cycle_partialGraph() {
        // 0 (ok), 1 → 2 → 1 (cycle)
        List<ExecutionPlan.PlanStep> steps = List.of(
                step(0, null),
                step(1, List.of(2)),
                step(2, List.of(1))
        );
        assertThat(dagExecutor.hasCycle(steps)).isTrue();
    }

    @Test
    void noCycle_longChain() {
        // 0 → 1 → 2 → 3 → 4
        List<ExecutionPlan.PlanStep> steps = List.of(
                step(0, null),
                step(1, List.of(0)),
                step(2, List.of(1)),
                step(3, List.of(2)),
                step(4, List.of(3))
        );
        assertThat(dagExecutor.hasCycle(steps)).isFalse();
    }
}
