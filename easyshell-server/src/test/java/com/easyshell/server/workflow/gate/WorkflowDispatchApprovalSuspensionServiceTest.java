package com.easyshell.server.workflow.gate;

import com.easyshell.server.model.entity.WorkflowNodeRun;
import com.easyshell.server.model.entity.WorkflowRun;
import com.easyshell.server.repository.WorkflowDispatchApprovalBindingRepository;
import com.easyshell.server.repository.WorkflowNodeRunRepository;
import com.easyshell.server.repository.WorkflowRunRepository;
import com.easyshell.server.workflow.approval.WorkflowApprovalStore;
import com.easyshell.server.workflow.approval.WorkflowApprovalAttemptBinding;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:dispatch_suspension_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@Import({WorkflowApprovalStore.class, WorkflowApprovalAttemptBinding.class, WorkflowDispatchApprovalSuspensionService.class,
        WorkflowDispatchApprovalBindingRepository.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WorkflowDispatchApprovalSuspensionServiceTest {
    @Autowired WorkflowDispatchApprovalSuspensionService service;
    @Autowired WorkflowNodeRunRepository nodes;
    @Autowired WorkflowRunRepository runs;
    @Autowired WorkflowDispatchApprovalBindingRepository bindingRepository;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @MockBean WorkflowDispatchApprovalBindingService bindings;

    private WorkflowDispatchApprovalBindingService.Intent intent(String id) {
        return new WorkflowDispatchApprovalBindingService.Intent("run-" + id, id, 1, 7,
                "example", List.of(new DispatchIntentHmac.Target("agent", 1)), 30);
    }

    private WorkflowDispatchApprovalBindingService.Intent setup() {
        String id = UUID.randomUUID().toString();
        var intent = intent(id);
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            WorkflowRun run = new WorkflowRun();
            run.setId(intent.runId());
            run.setWorkflowId(1L);
            run.setVersionId(1L);
            run.setVersionNo(1);
            run.setStatus("running");
            run.setStartedBy(intent.userId());
            runs.saveAndFlush(run);
            WorkflowNodeRun node = new WorkflowNodeRun();
            node.setId(id);
            node.setRunId(intent.runId());
            node.setNodeKey("step");
            node.setNodeType("script-exec");
            node.setStatus("running");
            nodes.saveAndFlush(node);
        });
        return intent;
    }

    private void persistBinding() {
        doAnswer(invocation -> {
            long id = invocation.getArgument(0);
            WorkflowDispatchApprovalBindingService.Intent intent = invocation.getArgument(1);
            assertThat(bindingRepository.create(id, intent.runId(), intent.nodeRunId(), intent.attempt(),
                    intent.userId(), "a".repeat(64), "b".repeat(64), "[]", intent.timeoutSec())).isEqualTo(1);
            return null;
        }).when(bindings).create(anyLong(), any());
    }

    private int count(String table, String column, Object value) {
        return jdbc.queryForObject("select count(*) from " + table + " where " + column + " = ?", Integer.class, value);
    }

    @Test
    void commitsApprovalBindingAndSuspensionTogether() {
        var intent = setup();
        persistBinding();
        long approvalId = new TransactionTemplate(manager).execute(tx -> service.suspend("step", intent, 120));
        assertThat(count("workflow_approval", "id", approvalId)).isEqualTo(1);
        assertThat(count("workflow_dispatch_approval_binding", "approval_id", approvalId)).isEqualTo(1);
        WorkflowNodeRun node = nodes.findById(intent.nodeRunId()).orElseThrow();
        assertThat(node.getStatus()).isEqualTo("waiting");
        assertThat(node.getApprovalId()).isEqualTo(approvalId);
    }

    @Test
    void callerRollbackRemovesAllWrites() {
        var intent = setup();
        persistBinding();
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            service.suspend("step", intent, 120);
            tx.setRollbackOnly();
        });
        assertThat(count("workflow_approval", "run_id", intent.runId())).isZero();
        assertThat(count("workflow_dispatch_approval_binding", "dispatch_node_run_id", intent.nodeRunId())).isZero();
        assertThat(nodes.findById(intent.nodeRunId()).orElseThrow().getStatus()).isEqualTo("running");
    }

    @Test
    void bindingFailureRollsBackApprovalAndMissingFactsFailClosed() {
        var intent = setup();
        doThrow(new IllegalArgumentException("missing HMAC key")).when(bindings).create(anyLong(), any());
        assertThatThrownBy(() -> new TransactionTemplate(manager).execute(tx -> service.suspend("step", intent, 120)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(count("workflow_approval", "run_id", intent.runId())).isZero();
        assertThat(nodes.findById(intent.nodeRunId()).orElseThrow().getStatus()).isEqualTo("running");
        assertThatThrownBy(() -> service.suspend("step", intent, 120)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> new TransactionTemplate(manager).execute(tx -> service.suspend("step", null, 120)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failedConditionalUpdateRollsBackApprovalAndBinding() {
        var intent = setup();
        doAnswer(invocation -> {
            long id = invocation.getArgument(0);
            bindingRepository.create(id, intent.runId(), intent.nodeRunId(), intent.attempt(), intent.userId(),
                    "a".repeat(64), "b".repeat(64), "[]", intent.timeoutSec());
            jdbc.update("update workflow_node_run set status = 'cancelled' where id = ?", intent.nodeRunId());
            return null;
        }).when(bindings).create(anyLong(), any());
        assertThatThrownBy(() -> new TransactionTemplate(manager).execute(tx -> service.suspend("step", intent, 120)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count("workflow_approval", "run_id", intent.runId())).isZero();
        assertThat(count("workflow_dispatch_approval_binding", "dispatch_node_run_id", intent.nodeRunId())).isZero();
        assertThat(nodes.findById(intent.nodeRunId()).orElseThrow().getStatus()).isEqualTo("running");
    }

    @Test
    void caughtBindingFailureStillRollsBackOuterCommit() {
        var intent = setup();
        doAnswer(invocation -> {
            assertThat(count("workflow_approval", "run_id", intent.runId())).isEqualTo(1);
            throw new IllegalStateException("injected binding failure");
        }).when(bindings).create(anyLong(), any());

        Throwable commitFailure = catchThrowable(() -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
            assertThatThrownBy(() -> service.suspend("step", intent, 120))
                    .isInstanceOf(IllegalStateException.class).hasMessage("injected binding failure");
            // Deliberately do not mark rollback-only: the outer caller attempts a commit.
        }));

        assertNoSuspensionEvidence(intent);
        assertThat(commitFailure).isInstanceOf(UnexpectedRollbackException.class);
    }

    @Test
    void caughtFailureAfterBindingStillRollsBackOuterCommit() {
        var intent = setup();
        doAnswer(invocation -> {
            long approvalId = invocation.getArgument(0);
            assertThat(bindingRepository.create(approvalId, intent.runId(), intent.nodeRunId(), intent.attempt(),
                    intent.userId(), "a".repeat(64), "b".repeat(64), "[]", intent.timeoutSec())).isEqualTo(1);
            assertThat(count("workflow_approval", "run_id", intent.runId())).isEqualTo(1);
            assertThat(count("workflow_dispatch_approval_binding", "dispatch_node_run_id", intent.nodeRunId())).isEqualTo(1);
            // Make the real conditional node update fail after both inserts have occurred.
            jdbc.update("update workflow_node_run set status = 'cancelled' where id = ?", intent.nodeRunId());
            return null;
        }).when(bindings).create(anyLong(), any());

        Throwable commitFailure = catchThrowable(() -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
            assertThatThrownBy(() -> service.suspend("step", intent, 120))
                    .isInstanceOf(IllegalStateException.class).hasMessage("Cannot suspend dispatch attempt");
        }));

        assertNoSuspensionEvidence(intent);
        assertThat(commitFailure).isInstanceOf(UnexpectedRollbackException.class);
    }

    private void assertNoSuspensionEvidence(WorkflowDispatchApprovalBindingService.Intent intent) {
        assertThat(count("workflow_approval", "run_id", intent.runId())).isZero();
        assertThat(count("workflow_dispatch_approval_binding", "dispatch_node_run_id", intent.nodeRunId())).isZero();
        WorkflowNodeRun node = nodes.findById(intent.nodeRunId()).orElseThrow();
        assertThat(node.getStatus()).isEqualTo("running");
        assertThat(node.getApprovalId()).isNull();
        assertThat(node.getTaskId()).isNull();
    }

    @Test
    void rejectsMissingNonRunningCancelledAndWrongOwnerRuns() {
        var intent = setup();
        for (String status : List.of("pending", "queued", "waiting", "succeeded", "failed", "cancelled", "timeout", "interrupted")) {
            jdbc.update("update workflow_run set status = ? where id = ?", status, intent.runId());
            assertThatThrownBy(() -> new TransactionTemplate(manager).execute(tx -> service.suspend("step", intent, 120)))
                    .isInstanceOf(IllegalStateException.class);
        }
        jdbc.update("update workflow_run set status = 'running', cancel_requested = true where id = ?", intent.runId());
        assertThatThrownBy(() -> new TransactionTemplate(manager).execute(tx -> service.suspend("step", intent, 120)))
                .isInstanceOf(IllegalStateException.class);
        jdbc.update("update workflow_run set cancel_requested = false, started_by = 8 where id = ?", intent.runId());
        assertThatThrownBy(() -> new TransactionTemplate(manager).execute(tx -> service.suspend("step", intent, 120)))
                .isInstanceOf(IllegalStateException.class);
        jdbc.update("update workflow_run set started_by = null where id = ?", intent.runId());
        assertThatThrownBy(() -> new TransactionTemplate(manager).execute(tx -> service.suspend("step", intent, 120)))
                .isInstanceOf(IllegalStateException.class);
        jdbc.update("delete from workflow_run where id = ?", intent.runId());
        assertThatThrownBy(() -> new TransactionTemplate(manager).execute(tx -> service.suspend("step", intent, 120)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count("workflow_approval", "run_id", intent.runId())).isZero();
        assertThat(count("workflow_dispatch_approval_binding", "dispatch_node_run_id", intent.nodeRunId())).isZero();
        assertThat(nodes.findById(intent.nodeRunId()).orElseThrow().getStatus()).isEqualTo("running");
    }

    @Test
    void cancellationHoldingRunLockBlocksSuspensionThenIsObserved() throws Exception {
        var intent = setup();
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var attempting = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var cancel = pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
                runs.lockById(intent.runId()).orElseThrow();
                runs.requestCancelAtomically(intent.runId());
                locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var suspend = pool.submit(() -> {
                attempting.countDown();
                return new TransactionTemplate(manager).execute(tx -> service.suspend("step", intent, 120));
            });
            assertThat(attempting.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> suspend.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            cancel.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> suspend.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(IllegalStateException.class);
            assertThat(count("workflow_approval", "run_id", intent.runId())).isZero();
            assertThat(nodes.findById(intent.nodeRunId()).orElseThrow().getStatus()).isEqualTo("running");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
