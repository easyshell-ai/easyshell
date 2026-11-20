package com.easyshell.server.workflow.approval;

import com.easyshell.server.model.entity.WorkflowNodeRun;
import com.easyshell.server.model.entity.WorkflowRun;
import com.easyshell.server.repository.WorkflowNodeRunRepository;
import com.easyshell.server.repository.WorkflowRunRepository;
import com.easyshell.server.workflow.engine.NodeContext;
import com.easyshell.server.workflow.engine.WorkflowEvent;
import com.easyshell.server.workflow.model.WorkflowNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({WorkflowApprovalStore.class, WorkflowApprovalAttemptBinding.class, WorkflowApprovalSuspensionService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WorkflowApprovalSuspensionRepositoryTest {
    @Autowired WorkflowApprovalSuspensionService service;
    @Autowired WorkflowRunRepository runs;
    @Autowired WorkflowNodeRunRepository nodes;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @SpyBean WorkflowApprovalStore approvals;

    private NodeContext setup(List<WorkflowEvent> events) {
        String id = UUID.randomUUID().toString();
        WorkflowRun run = new WorkflowRun();
        run.setId("run-" + id); run.setWorkflowId(1L); run.setVersionId(1L);
        run.setVersionNo(1); run.setStatus("running"); run.setStartedBy(7L);
        WorkflowNodeRun attempt = new WorkflowNodeRun();
        attempt.setId(id); attempt.setRunId(run.getId()); attempt.setNodeKey("step");
        attempt.setNodeType("approval"); attempt.setStatus("running");
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            runs.saveAndFlush(run); nodes.saveAndFlush(attempt);
        });
        WorkflowNode node = new WorkflowNode(); node.setKey("step"); node.setType("approval");
        return new NodeContext(run, attempt, node, null, null, null, new AtomicBoolean(),
                new AtomicBoolean(), false, events::add, null);
    }

    private int count(NodeContext ctx) {
        return jdbc.queryForObject("select count(*) from workflow_approval where run_id = ?", Integer.class, ctx.run().getId());
    }

    @Test
    void commitsBindingAndEventsAndPreservesRole() {
        List<WorkflowEvent> events = new CopyOnWriteArrayList<>();
        NodeContext ctx = setup(events);
        long id = service.suspend(ctx, "title", "detail", "SUPER_ADMIN", 120);
        WorkflowNodeRun actual = nodes.findById(ctx.nodeRun().getId()).orElseThrow();
        assertThat(actual.getStatus()).isEqualTo("waiting");
        assertThat(actual.getApprovalId()).isEqualTo(id);
        assertThat(approvals.findById(id).orElseThrow().getApproverRole()).isEqualTo("SUPER_ADMIN");
        assertThat(events).hasSize(2);
    }

    @Test
    void suspensionCommitsAndEmitsEvenWhenOuterTransactionRollsBack() {
        List<WorkflowEvent> events = new CopyOnWriteArrayList<>();
        NodeContext ctx = setup(events);
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            service.suspend(ctx, "title", "detail", "ADMIN", 120);
            tx.setRollbackOnly();
        });
        assertThat(count(ctx)).isEqualTo(1);
        assertThat(nodes.findById(ctx.nodeRun().getId()).orElseThrow().getStatus()).isEqualTo("waiting");
        assertThat(events).hasSize(2);
    }

    @Test
    void failureAfterInsertRollsBackAndPublishesNothing() {
        List<WorkflowEvent> events = new CopyOnWriteArrayList<>();
        NodeContext ctx = setup(events);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("injected failure after insert");
        }).when(approvals).createInTransaction(anyString(), anyString(), anyString(), anyString(),
                any(), any(), any(), anyString(), anyInt());
        assertThatThrownBy(() -> service.suspend(ctx, "title", "detail", "ADMIN", 120))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count(ctx)).isZero();
        assertThat(nodes.findById(ctx.nodeRun().getId()).orElseThrow().getStatus()).isEqualTo("running");
        assertThat(events).isEmpty();
    }

    @Test
    void cancelledRunAndTimedOutAttemptFailClosed() {
        List<WorkflowEvent> events = new CopyOnWriteArrayList<>();
        NodeContext cancelled = setup(events);
        new TransactionTemplate(manager).executeWithoutResult(tx -> runs.requestCancelAtomically(cancelled.run().getId()));
        assertThatThrownBy(() -> service.suspend(cancelled, "title", "detail", "ADMIN", 120));
        NodeContext timedOut = setup(events);
        timedOut.nodeCancelled().set(true);
        assertThatThrownBy(() -> service.suspend(timedOut, "title", "detail", "ADMIN", 120));
        assertThat(count(cancelled)).isZero();
        assertThat(count(timedOut)).isZero();
        assertThat(events).isEmpty();
    }

    @Test
    void concurrentAttemptsCreateOnlyOneApproval() throws Exception {
        List<WorkflowEvent> events = new CopyOnWriteArrayList<>();
        NodeContext ctx = setup(events);
        NodeContext competitor = new NodeContext(ctx.run(), ctx.nodeRun(), ctx.node(), null, null, null,
                new AtomicBoolean(), new AtomicBoolean(), false, events::add, null);
        var pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            var first = pool.submit(() -> { start.await(); try { service.suspend(ctx, "title", "detail", "ADMIN", 120); return 1; } catch (RuntimeException e) { return 0; } });
            var second = pool.submit(() -> { start.await(); try { service.suspend(competitor, "title", "detail", "ADMIN", 120); return 1; } catch (RuntimeException e) { return 0; } });
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(count(ctx)).isEqualTo(1);
            assertThat(events).hasSize(2);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void cancellationHoldingRunLockPreventsLateApproval() throws Exception {
        List<WorkflowEvent> events = new CopyOnWriteArrayList<>();
        NodeContext ctx = setup(events);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var cancellation = pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
                runs.lockById(ctx.run().getId()).orElseThrow();
                runs.requestCancelAtomically(ctx.run().getId());
                locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var suspension = pool.submit(() -> {
                try { service.suspend(ctx, "title", "detail", "ADMIN", 120); return true; }
                catch (RuntimeException e) { return false; }
            });
            release.countDown();
            cancellation.get(10, TimeUnit.SECONDS);
            assertThat(suspension.get(10, TimeUnit.SECONDS)).isFalse();
            assertThat(count(ctx)).isZero();
            assertThat(events).isEmpty();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
