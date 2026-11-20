package com.easyshell.server.workflow.approval;

import com.easyshell.server.model.entity.WorkflowApproval;
import com.easyshell.server.model.entity.WorkflowNodeRun;
import com.easyshell.server.model.entity.WorkflowRun;
import com.easyshell.server.repository.WorkflowNodeRunRepository;
import com.easyshell.server.repository.WorkflowRunRepository;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.workflow.engine.WorkflowEngine;
import com.easyshell.server.workflow.engine.WorkflowRecoveryClaimService;
import com.easyshell.server.workflow.properties.WorkflowProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Candidate filtering only; database claim serialization is covered by durable integration tests. */
class WorkflowResumeDurableScannerTest {
    private final WorkflowApprovalStore approvals = mock(WorkflowApprovalStore.class);
    private final WorkflowRunRepository runs = mock(WorkflowRunRepository.class);
    private final WorkflowNodeRunRepository nodes = mock(WorkflowNodeRunRepository.class);
    private final WorkflowEngine engine = mock(WorkflowEngine.class);
    private final WorkflowRecoveryClaimService claims = mock(WorkflowRecoveryClaimService.class);
    private final WorkflowProperties properties = new WorkflowProperties();
    private WorkflowResumeService service;
    private WorkflowRun run;
    private WorkflowNodeRun node;

    @BeforeEach
    void setUp() {
        properties.getEngine().setDurableResumeEnabled(true);
        properties.getEngine().setAutomaticRecoveryEnabled(false);
        service = new WorkflowResumeService(approvals, runs, nodes, engine, mock(AuditLogService.class),
                properties, mock(PlatformTransactionManager.class));
        service.setRecoveryClaims(claims);
        run = new WorkflowRun();
        run.setId("run");
        run.setStatus("waiting");
        node = new WorkflowNodeRun();
        node.setRunId("run");
        node.setNodeKey("approval");
        node.setNodeType("approval");
        node.setApprovalId(1L);
        when(nodes.findWaitingRunIdsAfter(isNull(), any(Pageable.class))).thenReturn(List.of("run"));
        when(runs.findById("run")).thenReturn(Optional.of(run));
    }

    @Test
    void lostNotificationResumesWaitingRunOnceWithoutReadingClaimIdentity() {
        var approval = new WorkflowApproval();
        approval.setRunId("run");
        approval.setNodeKey("approval");
        approval.setNodeType("approval");
        approval.setStatus("approved");
        when(nodes.findByRunIdAndStatus("run", "waiting")).thenReturn(List.of(node));
        when(approvals.findById(1L)).thenReturn(Optional.of(approval));

        service.scanWakeups();

        verify(engine).submitResume("run");
        verifyNoMoreInteractions(engine);
        verify(claims).hasActiveBatch("run");
        verifyNoMoreInteractions(claims);
        verify(nodes, never()).findByStatus(anyString());
    }

    @Test
    void activeBatchIsNotResumed() {
        when(claims.hasActiveBatch("run")).thenReturn(true);

        service.scanWakeups();

        verifyNoInteractions(engine, approvals);
        verify(nodes, never()).findByRunIdAndStatus("run", "waiting");
    }

    @Test
    void runningRunWithWaitingRowsIsNotResumedOrReadAsClaim() {
        run.setStatus("running");

        service.scanWakeups();

        verifyNoInteractions(engine, approvals, claims);
        verify(nodes, never()).findByRunIdAndStatus("run", "waiting");
    }

    @Test
    void missingClaimServiceFailsClosedForOldConstructorCallers() {
        service.setRecoveryClaims(null);

        service.scanWakeups();

        verifyNoInteractions(engine, approvals, claims);
    }

    @Test
    void claimQueryFailureDoesNotAuthorizeResume() {
        when(claims.hasActiveBatch("run")).thenThrow(new IllegalStateException("unavailable"));

        service.scanWakeups();

        verifyNoInteractions(engine, approvals);
    }

    @Test
    void bothFlagsDisabledLeaveScannerInactive() {
        properties.getEngine().setDurableResumeEnabled(false);

        service.scanWakeups();

        verifyNoInteractions(engine, approvals, claims);
        verify(nodes, never()).findByStatus("waiting");
        verify(nodes, never()).findWaitingRunIdsAfter(any(), any(Pageable.class));
    }

    @Test
    void cursorAdvancesPastOneHundredPoisonCandidatesAndWrapsAfterTail() {
        List<String> first = IntStream.range(0, 100).mapToObj(i -> String.format("a-%03d", i)).toList();
        when(nodes.findWaitingRunIdsAfter(isNull(), any(Pageable.class))).thenReturn(first);
        for (String id : first) when(runs.findById(id)).thenThrow(new IllegalStateException("poison"));
        when(nodes.findWaitingRunIdsAfter(eq("a-099"), any(Pageable.class))).thenReturn(List.of("run"));
        readyApproval();

        service.scanWakeups();
        verifyNoInteractions(engine);
        service.scanWakeups();
        verify(engine).submitResume("run");
        service.scanWakeups();

        verify(nodes, times(2)).findWaitingRunIdsAfter(isNull(), argThat(page ->
                page.getPageNumber() == 0 && page.getPageSize() == 100));
        verify(nodes).findWaitingRunIdsAfter(eq("a-099"), argThat(page -> page.getPageSize() == 100));
        verify(nodes, never()).findByStatus(anyString());
    }

    @Test
    void duplicateCandidatesSubmitOnceAndRejectedSubmissionRetriesNextPass() {
        when(nodes.findWaitingRunIdsAfter(isNull(), any(Pageable.class))).thenReturn(List.of("run", "run"));
        readyApproval();
        doThrow(new RejectedExecutionException("sensitive error must not be logged"))
                .doNothing().when(engine).submitResume("run");

        service.scanWakeups();
        assertThat(run.getStatus()).isEqualTo("waiting");
        verify(engine).submitResume("run");
        service.scanWakeups();

        verify(engine, times(2)).submitResume("run");
        verify(engine, never()).resumeRun(anyString());
        verify(claims, times(2)).hasActiveBatch("run");
        verifyNoMoreInteractions(claims);
    }

    @ParameterizedTest
    @ValueSource(strings = {"run", "node", "type", "pending", "unknown", "missing", "unbound", "secondPending"})
    void incompleteOrMismatchedDecisionNeverSubmits(String scenario) {
        WorkflowApproval approval = readyApproval();
        switch (scenario) {
            case "run" -> approval.setRunId("other");
            case "node" -> approval.setNodeKey("other");
            case "type" -> approval.setNodeType("script-exec");
            case "pending" -> approval.setStatus("pending");
            case "unknown" -> approval.setStatus("unknown");
            case "missing" -> when(approvals.findById(1L)).thenReturn(Optional.empty());
            case "unbound" -> node.setApprovalId(null);
            case "secondPending" -> {
                WorkflowNodeRun second = new WorkflowNodeRun();
                second.setRunId("run");
                second.setNodeKey("second");
                second.setNodeType("approval");
                second.setApprovalId(2L);
                WorkflowApproval pending = new WorkflowApproval();
                pending.setRunId("run");
                pending.setNodeKey("second");
                pending.setNodeType("approval");
                pending.setStatus("pending");
                when(approvals.findById(2L)).thenReturn(Optional.of(pending));
                when(nodes.findByRunIdAndStatus("run", "waiting")).thenReturn(List.of(node, second));
            }
            default -> throw new AssertionError(scenario);
        }

        service.scanWakeups();

        verifyNoInteractions(engine);
    }

    @Test
    void discoveryFailureDoesNotAuthorizeOrAdvanceCursor() {
        when(nodes.findWaitingRunIdsAfter(isNull(), any(Pageable.class)))
                .thenThrow(new IllegalStateException("unavailable")).thenReturn(List.of("run"));
        readyApproval();
        service.scanWakeups();
        verifyNoInteractions(engine, claims);
        service.scanWakeups();
        verify(engine).submitResume("run");
        verify(nodes, times(2)).findWaitingRunIdsAfter(isNull(), any(Pageable.class));
    }

    private WorkflowApproval readyApproval() {
        WorkflowApproval approval = new WorkflowApproval();
        approval.setRunId("run");
        approval.setNodeKey("approval");
        approval.setNodeType("approval");
        approval.setStatus("approved");
        when(nodes.findByRunIdAndStatus("run", "waiting")).thenReturn(List.of(node));
        when(approvals.findById(1L)).thenReturn(Optional.of(approval));
        return approval;
    }
}
