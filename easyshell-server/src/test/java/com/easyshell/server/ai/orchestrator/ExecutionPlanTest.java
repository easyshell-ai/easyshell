package com.easyshell.server.ai.orchestrator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ExecutionPlan")
class ExecutionPlanTest {

    @Test
    void builder_defaults() {
        ExecutionPlan plan = ExecutionPlan.builder().build();
        assertThat(plan.getSteps()).isNotNull().isEmpty();
        assertThat(plan.isRequiresConfirmation()).isFalse();
        assertThat(plan.getEstimatedRisk()).isNull();
        assertThat(plan.getSummary()).isNull();
    }

    @Test
    void planStep_defaultStatus() {
        ExecutionPlan.PlanStep step = ExecutionPlan.PlanStep.builder().build();
        assertThat(step.getStatus()).isEqualTo("pending");
    }

    @Test
    void planStep_allFields() {
        ExecutionPlan.PlanStep step = ExecutionPlan.PlanStep.builder()
                .index(2)
                .description("Install package")
                .agent("executor")
                .status("completed")
                .tools(List.of("script_execute"))
                .hosts(List.of("host-1", "host-2"))
                .parallelGroup(1)
                .rollbackHint("apt remove package")
                .result("ok")
                .error(null)
                .build();

        assertThat(step.getIndex()).isEqualTo(2);
        assertThat(step.getDescription()).isEqualTo("Install package");
        assertThat(step.getAgent()).isEqualTo("executor");
        assertThat(step.getStatus()).isEqualTo("completed");
        assertThat(step.getTools()).containsExactly("script_execute");
        assertThat(step.getHosts()).containsExactly("host-1", "host-2");
        assertThat(step.getParallelGroup()).isEqualTo(1);
        assertThat(step.getRollbackHint()).isEqualTo("apt remove package");
        assertThat(step.getResult()).isEqualTo("ok");
        assertThat(step.getError()).isNull();
    }

    @Test
    void plan_withSteps() {
        ExecutionPlan plan = ExecutionPlan.builder()
                .summary("Deploy to 3 hosts")
                .requiresConfirmation(true)
                .estimatedRisk("HIGH")
                .steps(List.of(
                        ExecutionPlan.PlanStep.builder().index(0).description("Check hosts").build(),
                        ExecutionPlan.PlanStep.builder().index(1).description("Deploy").parallelGroup(1).build(),
                        ExecutionPlan.PlanStep.builder().index(2).description("Verify").build()
                ))
                .build();

        assertThat(plan.getSummary()).isEqualTo("Deploy to 3 hosts");
        assertThat(plan.isRequiresConfirmation()).isTrue();
        assertThat(plan.getEstimatedRisk()).isEqualTo("HIGH");
        assertThat(plan.getSteps()).hasSize(3);
        assertThat(plan.getSteps().get(1).getParallelGroup()).isEqualTo(1);
    }
}
