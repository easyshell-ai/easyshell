package com.easyshell.server.ai.orchestrator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AgentEvent factory methods")
class AgentEventTest {

    @Test
    void session_setsSessionId() {
        AgentEvent e = AgentEvent.session("sess-1");
        assertThat(e.getType()).isEqualTo(AgentEvent.Type.SESSION);
        assertThat(e.getSessionId()).isEqualTo("sess-1");
    }

    @Test
    void error_setsContent() {
        AgentEvent e = AgentEvent.error("boom");
        assertThat(e.getType()).isEqualTo(AgentEvent.Type.ERROR);
        assertThat(e.getContent()).isEqualTo("boom");
    }

    @Test
    void heartbeat_isThinkingWithSystemAgent() {
        AgentEvent e = AgentEvent.heartbeat("alive");
        assertThat(e.getType()).isEqualTo(AgentEvent.Type.THINKING);
        assertThat(e.getAgent()).isEqualTo("system");
        assertThat(e.getContent()).isEqualTo("alive");
    }

    @Test
    void planAwaitConfirmation_setsTypeAndPlan() {
        ExecutionPlan plan = ExecutionPlan.builder().summary("test").build();
        AgentEvent e = AgentEvent.planAwaitConfirmation(plan);
        assertThat(e.getType()).isEqualTo(AgentEvent.Type.PLAN_AWAIT_CONFIRMATION);
        assertThat(e.getPlan()).isSameAs(plan);
    }

    @Test
    void planConfirmed_setsTypeAndSessionId() {
        AgentEvent e = AgentEvent.planConfirmed("s1");
        assertThat(e.getType()).isEqualTo(AgentEvent.Type.PLAN_CONFIRMED);
        assertThat(e.getSessionId()).isEqualTo("s1");
    }

    @Test
    void planRejected_setsTypeAndSessionId() {
        AgentEvent e = AgentEvent.planRejected("s2");
        assertThat(e.getType()).isEqualTo(AgentEvent.Type.PLAN_REJECTED);
        assertThat(e.getSessionId()).isEqualTo("s2");
    }

    @Test
    void stepRetry_setsTypeIndexAndContent() {
        AgentEvent e = AgentEvent.stepRetry(3, "retrying step 3");
        assertThat(e.getType()).isEqualTo(AgentEvent.Type.STEP_RETRY);
        assertThat(e.getStepIndex()).isEqualTo(3);
        assertThat(e.getContent()).isEqualTo("retrying step 3");
    }

    @Test
    void planSummary_setsContentAndPlan() {
        ExecutionPlan plan = ExecutionPlan.builder().summary("done").build();
        AgentEvent e = AgentEvent.planSummary("3/3 completed", plan);
        assertThat(e.getType()).isEqualTo(AgentEvent.Type.PLAN_SUMMARY);
        assertThat(e.getContent()).isEqualTo("3/3 completed");
        assertThat(e.getPlan()).isSameAs(plan);
    }

    @Test
    void reviewStart_setsTypeAndAgent() {
        AgentEvent e = AgentEvent.reviewStart();
        assertThat(e.getType()).isEqualTo(AgentEvent.Type.REVIEW_START);
        assertThat(e.getAgent()).isEqualTo("reviewer");
    }

    @Test
    void reviewComplete_setsContentAndAgent() {
        AgentEvent e = AgentEvent.reviewComplete("PASS");
        assertThat(e.getType()).isEqualTo(AgentEvent.Type.REVIEW_COMPLETE);
        assertThat(e.getContent()).isEqualTo("PASS");
        assertThat(e.getAgent()).isEqualTo("reviewer");
    }

    @Test
    void parallelStart_setsGroupAndTotal() {
        AgentEvent e = AgentEvent.parallelStart(1, 4);
        assertThat(e.getType()).isEqualTo(AgentEvent.Type.PARALLEL_START);
        assertThat(e.getParallelGroup()).isEqualTo(1);
        assertThat(e.getTotalTasks()).isEqualTo(4);
    }

    @Test
    void parallelProgress_setsAllFields() {
        AgentEvent e = AgentEvent.parallelProgress(2, 3, 5);
        assertThat(e.getType()).isEqualTo(AgentEvent.Type.PARALLEL_PROGRESS);
        assertThat(e.getParallelGroup()).isEqualTo(2);
        assertThat(e.getCompletedTasks()).isEqualTo(3);
        assertThat(e.getTotalTasks()).isEqualTo(5);
    }

    @Test
    void parallelComplete_setsGroup() {
        AgentEvent e = AgentEvent.parallelComplete(7);
        assertThat(e.getType()).isEqualTo(AgentEvent.Type.PARALLEL_COMPLETE);
        assertThat(e.getParallelGroup()).isEqualTo(7);
    }

    @Test
    void subtaskStarted_setsMetadata() {
        AgentEvent e = AgentEvent.subtaskStarted("t1", "executor", "run script");
        assertThat(e.getType()).isEqualTo(AgentEvent.Type.SUBTASK_STARTED);
        assertThat(e.getAgent()).isEqualTo("executor");
        assertThat(e.getContent()).isEqualTo("run script");
        assertThat(e.getMetadata()).containsEntry("taskId", "t1");
    }

    @Test
    void subtaskCompleted_truncatesLongResult() {
        String longResult = "x".repeat(600);
        AgentEvent e = AgentEvent.subtaskCompleted("t2", longResult);
        assertThat(e.getType()).isEqualTo(AgentEvent.Type.SUBTASK_COMPLETED);
        assertThat(e.getContent()).hasSize(503).endsWith("...");
    }
}
