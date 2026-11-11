package com.easyshell.server.workflow.service;

import com.easyshell.server.model.entity.WorkflowApproval;
import com.easyshell.server.model.entity.WorkflowNodeRun;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NonHostV3RecoveryValidatorTest {
    private static final String GRAPH = """
            {"nodes":[{"key":"gate","type":"approval"},{"key":"middle","type":"condition"},
            {"key":"end","type":"end"},{"key":"other","type":"start"}],
            "edges":[{"from":"gate","to":"middle"},{"from":"middle","to":"end"}]}
            """;
    private final NonHostV3RecoveryValidator validator = new NonHostV3RecoveryValidator(new ObjectMapper());

    private WorkflowApproval approval() {
        WorkflowApproval a = new WorkflowApproval();
        a.setId(12L); a.setRunId("run"); a.setNodeKey("gate"); a.setNodeType("approval");
        a.setStatus("approved"); a.setDecidedBy(42L);
        return a;
    }

    private WorkflowNodeRun attempt(String key, String type, String status) {
        WorkflowNodeRun n = new WorkflowNodeRun();
        n.setRunId("run"); n.setNodeKey(key); n.setNodeType(type); n.setStatus(status);
        return n;
    }

    private WorkflowNodeRun succeeded() {
        WorkflowNodeRun n = attempt("gate", "approval", "succeeded");
        n.setApprovalId(12L); n.setOutputJson("{\"decision\":\"approved\",\"decidedBy\":42}");
        return n;
    }

    private boolean allows(WorkflowNodeRun n, WorkflowApproval a) {
        return validator.allows("run", GRAPH, List.of(n), List.of(a));
    }

    @Test void acceptsMatchingSnapshotAndTimeoutNullActor() {
        assertThat(allows(succeeded(), approval())).isTrue();
        WorkflowApproval a = approval(); a.setStatus("timeout"); a.setDecidedBy(null);
        WorkflowNodeRun n = succeeded(); n.setOutputJson("{\"decision\":\"timeout\",\"decidedBy\":null}");
        assertThat(allows(n, a)).isTrue();
        assertThat(validator.allows("run", GRAPH, List.of(), List.of())).isTrue();
    }

    @Test void rejectsUntrustedOutputs() {
        for (String output : List.of("{}", "null", "{", "{\"decision\":\"approved\",\"decidedBy\":42,\"comment\":null}",
                "{\"decision\":\"rejected\",\"decidedBy\":42}", "{\"decision\":\"approved\",\"decidedBy\":\"42\"}",
                "{\"decision\":\"approved\",\"decidedBy\":43}",
                "{\"decision\":\"approved\",\"decision\":\"approved\",\"decidedBy\":42}",
                "{\"decision\":\"approved\",\"decidedBy\":42} {}")) {
            WorkflowNodeRun n = succeeded(); n.setOutputJson(output);
            assertThat(allows(n, approval())).as(output).isFalse();
        }
        WorkflowApproval a = approval(); a.setStatus("rejected");
        assertThat(allows(succeeded(), a)).isFalse();
    }

    @Test void rejectsAllTransitiveDownstreamAttemptsRegardlessOfState() {
        for (String status : List.of("pending", "running", "waiting", "succeeded", "failed", "skipped", "cancelled", "timeout")) {
            assertThat(validator.allows("run", GRAPH, List.of(succeeded(), attempt("end", "end", status)), List.of(approval())))
                    .as(status).isFalse();
        }
        assertThat(validator.allows("run", GRAPH, List.of(succeeded(), attempt("other", "start", "succeeded")), List.of(approval()))).isTrue();
    }

    @Test void rejectsBadBindingsStatusesAndJson() {
        WorkflowNodeRun n = succeeded(); n.setApprovalId(99L);
        assertThat(allows(n, approval())).isFalse();
        WorkflowApproval a = approval(); a.setRunId("other");
        assertThat(allows(succeeded(), a)).isFalse();
        a = approval(); a.setNodeKey("other");
        assertThat(allows(succeeded(), a)).isFalse();
        a = approval(); a.setStatus("future");
        assertThat(allows(succeeded(), a)).isFalse();
        n = succeeded(); n.setStatus("future");
        assertThat(allows(n, approval())).isFalse();
        n = succeeded(); n.setInputJson("{");
        assertThat(allows(n, approval())).isFalse();
        assertThat(validator.allows("run", GRAPH, List.of(), List.of(approval()))).isFalse();
        assertThat(validator.allows("run", GRAPH, List.of(succeeded(), succeeded()), List.of(approval()))).isFalse();
        assertThat(validator.allows("run", GRAPH + " {}", List.of(), List.of())).isFalse();
        assertThat(validator.allows("run", "{\"nodes\":[],\"nodes\":[]}", List.of(), List.of())).isFalse();
    }

    @Test void rejectsApprovalFreeTextEvenWhenItIsValidJsonOrEmpty() {
        for (String text : List.of("", "{}", "{\"comment\":\"secret\"}")) {
            WorkflowNodeRun n = succeeded(); n.setInputJson(text);
            assertThat(allows(n, approval())).as("input %s", text).isFalse();
            n = succeeded(); n.setErrorMessage(text);
            assertThat(allows(n, approval())).as("error %s", text).isFalse();
            n = succeeded(); n.setWarningMessage(text);
            assertThat(allows(n, approval())).as("warning %s", text).isFalse();
            n = attempt("gate", "approval", "waiting"); n.setApprovalId(12L); n.setOutputJson(text);
            assertThat(allows(n, approval())).as("waiting output %s", text).isFalse();
        }
    }

    @Test void approvalStateAndBindingMustBeConsistent() {
        WorkflowNodeRun n = attempt("gate", "approval", "pending");
        assertThat(validator.allows("run", GRAPH, List.of(n), List.of())).isTrue();
        n.setApprovalId(12L);
        assertThat(allows(n, approval())).isFalse();
        n = attempt("gate", "approval", "waiting");
        assertThat(validator.allows("run", GRAPH, List.of(n), List.of())).isFalse();
        n.setApprovalId(12L);
        for (String status : List.of("pending", "approved", "rejected", "timeout", "cancelled")) {
            WorkflowApproval a = approval(); a.setStatus(status);
            assertThat(allows(n, a)).as(status).isTrue();
        }
        for (String status : List.of("failed", "skipped", "cancelled", "timeout")) {
            n = attempt("gate", "approval", status); n.setApprovalId(12L);
            assertThat(allows(n, approval())).as(status).isFalse();
        }
        n = attempt("gate", "approval", "running");
        assertThat(validator.allows("run", GRAPH, List.of(n), List.of())).isTrue();
        n.setApprovalId(12L);
        assertThat(allows(n, approval())).isTrue();
        n.setOutputJson("{}");
        assertThat(allows(n, approval())).isFalse();
    }
}
