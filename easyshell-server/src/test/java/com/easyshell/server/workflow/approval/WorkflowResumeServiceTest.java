package com.easyshell.server.workflow.approval;

import com.easyshell.server.model.entity.AuditLog;
import com.easyshell.server.model.entity.WorkflowApproval;
import com.easyshell.server.model.entity.WorkflowNodeRun;
import com.easyshell.server.model.entity.WorkflowRun;
import com.easyshell.server.repository.AuditLogRepository;
import com.easyshell.server.repository.WorkflowApprovalRepository;
import com.easyshell.server.repository.WorkflowNodeRunRepository;
import com.easyshell.server.repository.WorkflowRunRepository;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.workflow.engine.WorkflowEngine;
import com.easyshell.server.workflow.properties.WorkflowProperties;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WorkflowResumeServiceTest {
    @Autowired WorkflowApprovalRepository approvals;
    @Autowired AuditLogRepository audits;
    @Autowired WorkflowRunRepository runs;
    @Autowired WorkflowNodeRunRepository nodes;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactions;

    @ParameterizedTest
    @ValueSource(strings = {"missingId", "missingApproval", "run", "node", "type", "pending", "unknown", "approved", "script"})
    void resumeUsesOnlyTheWaitingAttemptBinding(String scenario) {
        WorkflowApprovalStore store = mock(WorkflowApprovalStore.class);
        WorkflowRunRepository runRepo = mock(WorkflowRunRepository.class);
        WorkflowNodeRunRepository nodeRepo = mock(WorkflowNodeRunRepository.class);
        WorkflowEngine engine = mock(WorkflowEngine.class);
        WorkflowRun run = new WorkflowRun();
        run.setStatus("waiting");
        WorkflowNodeRun row = new WorkflowNodeRun();
        row.setRunId("run");
        row.setNodeKey("gate");
        row.setNodeType("script".equals(scenario) ? "script-exec" : "approval");
        row.setApprovalId("missingId".equals(scenario) ? null : 42L);
        WorkflowApproval approval = new WorkflowApproval();
        approval.setRunId("run".equals(scenario) ? "other" : "run");
        approval.setNodeKey("node".equals(scenario) ? "other" : "gate");
        approval.setNodeType("type".equals(scenario) ? "other" : row.getNodeType());
        approval.setStatus("pending".equals(scenario) ? "pending" : "unknown".equals(scenario) ? "unexpected" : "approved");
        when(runRepo.findById("run")).thenReturn(Optional.of(run));
        when(nodeRepo.findByRunIdAndStatus("run", "waiting")).thenReturn(List.of(row));
        when(store.findById(42L)).thenReturn("missingApproval".equals(scenario) ? Optional.empty() : Optional.of(approval));
        new WorkflowResumeService(store, runRepo, nodeRepo, engine, mock(AuditLogService.class),
                new WorkflowProperties(), transactions).resumeIfReady("run");
        if ("approved".equals(scenario) || "script".equals(scenario)) {
            verify(engine).resumeRun("run");
        } else {
            verifyNoInteractions(engine);
        }
        verify(store, never()).findByRunAndNode(anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"approved", "rejected"})
    void auditFailureRollsBackDecisionAndDoesNotResume(String decision) {
        createRun("rollback-" + decision);
        WorkflowApprovalStore store = new WorkflowApprovalStore(approvals, entityManager, transactions);
        WorkflowApproval approval = approvalFixture("rollback-" + decision);
        long auditCount = audits.count();
        AuditLogService audit = mock(AuditLogService.class);
        WorkflowEngine engine = mock(WorkflowEngine.class);
        doAnswer(invocation -> {
            AuditLog row = new AuditLog();
            row.setUserId(7L);
            row.setUsername("SYSTEM");
            row.setAction("WORKFLOW_APPROVE");
            row.setResourceType("workflow_approval");
            row.setResourceId(approval.getId().toString());
            row.setResult("success");
            audits.saveAndFlush(row);
            throw new IllegalStateException("audit failure");
        }).when(audit).logSync(any(), any(), any(), any(), any(), any(), any(), any());
        WorkflowResumeService service = new WorkflowResumeService(store, runs, nodes, engine, audit,
                new WorkflowProperties(), transactions);

        assertThatThrownBy(() -> service.decide(approval.getId(), decision, 7L, "ok"))
                .isInstanceOf(IllegalStateException.class).hasMessage("audit failure");
        WorkflowApproval persisted = approvals.findById(approval.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo("pending");
        assertThat(persisted.getDecidedBy()).isNull();
        assertThat(persisted.getVersion()).isEqualTo(0);
        assertThat(audits.count()).isEqualTo(auditCount);
        verifyNoInteractions(engine);
    }

    @ParameterizedTest
    @ValueSource(strings = {"approved", "rejected"})
    void successfulDecisionCommitsBeforeResume(String decision) {
        createRun("success-" + decision);
        WorkflowApprovalStore store = new WorkflowApprovalStore(approvals, entityManager, transactions);
        WorkflowApproval approval = approvalFixture("success-" + decision);
        AuditLogService audit = mock(AuditLogService.class);
        WorkflowEngine engine = mock(WorkflowEngine.class);
        doAnswer(invocation -> {
            assertThat(approvals.findById(approval.getId()).orElseThrow().getStatus()).isEqualTo(decision);
            return null;
        }).when(engine).submitResume(approval.getRunId());
        WorkflowResumeService service = new WorkflowResumeService(store, runs, nodes, engine, audit,
                new WorkflowProperties(), transactions);

        String comment = "sensitive arbitrary approval comment";
        assertThat(service.decide(approval.getId(), decision, 7L, comment).getStatus()).isEqualTo(decision);
        assertThat(approvals.findById(approval.getId()).orElseThrow().getStatus()).isEqualTo(decision);
        assertThat(approvals.findById(approval.getId()).orElseThrow().getDecisionComment()).isEqualTo(comment);
        verify(audit).logSync(eq(7L), eq("SYSTEM"),
                eq("approved".equals(decision) ? "WORKFLOW_APPROVE" : "WORKFLOW_REJECT"),
                eq("workflow_approval"), eq(approval.getId().toString()),
                eq("run=" + approval.getRunId() + ", node=gate, decision=" + decision), isNull(), eq("success"));
        verify(engine).submitResume(approval.getRunId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"approved", "rejected"})
    void expiredPendingApprovalCannotBeDecidedOrResume(String decision) {
        String runId = "expired-" + decision;
        createRun(runId);
        WorkflowApprovalStore store = new WorkflowApprovalStore(approvals, entityManager, transactions);
        WorkflowApproval approval = approvalFixture(runId);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            WorkflowApproval current = approvals.findById(approval.getId()).orElseThrow();
            current.setExpiresAt(java.time.LocalDateTime.now().minusSeconds(1));
            approvals.saveAndFlush(current);
        });
        WorkflowEngine engine = mock(WorkflowEngine.class);
        AuditLogService audit = mock(AuditLogService.class);
        WorkflowResumeService service = new WorkflowResumeService(store, runs, nodes, engine, audit,
                new WorkflowProperties(), transactions);

        assertThatThrownBy(() -> service.decide(approval.getId(), decision, 7L, null))
                .isInstanceOf(com.easyshell.server.common.exception.BusinessException.class)
                .hasMessageContaining("APPROVAL_DECIDED");
        assertThat(approvals.findById(approval.getId()).orElseThrow().getStatus()).isEqualTo("pending");
        verifyNoInteractions(engine, audit);
    }

    @ParameterizedTest
    @ValueSource(strings = {"approved", "rejected", "timeout"})
    void approvalWritesWaitForRunLockWithoutBlockingItsOwner(String action) throws Exception {
        String runId = "lock-order-" + action;
        createRun(runId);
        WorkflowApprovalStore store = new WorkflowApprovalStore(approvals, entityManager, transactions);
        WorkflowApproval approval = approvalFixture(runId);
        WorkflowResumeService service = new WorkflowResumeService(store, runs, nodes, mock(WorkflowEngine.class),
                mock(AuditLogService.class), new WorkflowProperties(), transactions);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Future<?>> future = new AtomicReference<>();
        try {
            new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                runs.lockById(runId).orElseThrow();
                Future<?> competing = executor.submit(() -> {
                    started.countDown();
                    if ("timeout".equals(action)) {
                        assertThat(store.expire(approval.getId())).isFalse();
                    } else {
                        assertThatThrownBy(() -> service.decide(approval.getId(), action, 7L, null))
                                .isInstanceOf(com.easyshell.server.common.exception.BusinessException.class)
                                .hasMessageContaining("APPROVAL_DECIDED");
                    }
                });
                future.set(competing);
                try {
                    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> competing.get(300, TimeUnit.MILLISECONDS))
                            .isInstanceOf(TimeoutException.class);
                    // REQUIRED 必须加入当前事务，而不是在持有 run 锁时开启 REQUIRES_NEW。
                    assertThat(store.expire(approval.getId())).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            });
            future.get().get(10, TimeUnit.SECONDS);
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            assertThat(approvals.findById(approval.getId()).orElseThrow().getStatus()).isEqualTo("timeout");
        } finally {
            executor.shutdownNow();
        }
    }

    @org.junit.jupiter.api.Test
    void standaloneCreateFailsClosedEvenForExistingRun() {
        createRun("standalone-create");
        WorkflowApprovalStore store = new WorkflowApprovalStore(approvals, entityManager, transactions);
        long before = approvals.count();
        assertThatThrownBy(() -> store.create("standalone-create", "gate", "approval", "gate",
                null, null, null, "ADMIN", 60)).isInstanceOf(IllegalStateException.class);
        assertThat(approvals.count()).isEqualTo(before);
    }

    private WorkflowApproval approvalFixture(String runId) {
        WorkflowApproval approval = new WorkflowApproval();
        approval.setRunId(runId);
        approval.setNodeKey("gate");
        approval.setNodeType("approval");
        approval.setTitle("gate");
        approval.setExpiresAt(java.time.LocalDateTime.now().plusSeconds(60));
        return approvals.saveAndFlush(approval);
    }

    private void createRun(String id) {
        WorkflowRun run = new WorkflowRun();
        run.setId(id);
        run.setWorkflowId(1L);
        run.setVersionId(1L);
        run.setVersionNo(1);
        run.setStatus("running");
        runs.saveAndFlush(run);
    }
}
