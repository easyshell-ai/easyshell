package com.easyshell.server.workflow;

import com.easyshell.server.model.entity.WorkflowNodeRun;
import com.easyshell.server.model.entity.WorkflowRun;
import com.easyshell.server.repository.WorkflowNodeRunRepository;
import com.easyshell.server.repository.WorkflowRunRepository;
import com.easyshell.server.workflow.engine.AttemptAllocator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AttemptAllocatorH2Test {
    @Autowired WorkflowRunRepository runs;
    @Autowired WorkflowNodeRunRepository nodes;
    @Autowired PlatformTransactionManager transactions;

    private WorkflowRun run(String id) {
        WorkflowRun run = new WorkflowRun();
        run.setId(id);
        run.setWorkflowId(1L);
        run.setVersionId(1L);
        run.setVersionNo(1);
        run.setStatus("running");
        return runs.saveAndFlush(run);
    }

    @Test
    void timeoutCanRetryButTerminalTracesAndTerminalRunsCannotStart() {
        String id = "allocator-timeout";
        WorkflowRun run = run(id);
        AttemptAllocator allocator = new AttemptAllocator(nodes, runs, transactions);
        WorkflowNodeRun first = allocator.startAttempt(id, "node", "wait", 0);
        allocator.finishAttempt(first, AttemptAllocator.AttemptResult.builder().status("timeout").build());
        WorkflowNodeRun retry = allocator.startAttempt(id, "node", "wait", 1);
        assertThat(retry).isNotNull();
        assertThat(retry.getAttempt()).isEqualTo(2);
        assertThat(allocator.startAttempt(id, "node", "wait", 1)).isNull();
        allocator.finishAttempt(retry, AttemptAllocator.AttemptResult.builder().status("cancelled").build());
        assertThat(allocator.startAttempt(id, "node", "wait", 2)).isNull();
        WorkflowNodeRun trace = new WorkflowNodeRun();
        trace.setId("allocator-skipped-trace");
        trace.setRunId(id);
        trace.setNodeKey("skipped");
        trace.setNodeType("wait");
        trace.setAttempt(1);
        trace.setStatus("skipped");
        trace.setStartedAt(java.time.LocalDateTime.now());
        nodes.saveAndFlush(trace);
        assertThat(allocator.startAttempt(id, "skipped", "wait", 1)).isNull();
        run.setStatus("succeeded");
        runs.saveAndFlush(run);
        assertThat(allocator.startAttempt(id, "other", "wait", 0)).isNull();
        assertThat(nodes.findByRunId(id)).hasSize(3);
    }

    @Test
    void cancellationHoldingRunLockWinsOverRetry() throws Exception {
        String id = "allocator-cancel";
        run(id);
        AttemptAllocator allocator = new AttemptAllocator(nodes, runs, transactions);
        WorkflowNodeRun first = allocator.startAttempt(id, "node", "wait", 0);
        allocator.finishAttempt(first, AttemptAllocator.AttemptResult.builder().status("failed").build());
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch attempting = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var cancel = pool.submit(() -> new TransactionTemplate(transactions).execute(tx -> {
                runs.lockById(id).orElseThrow();
                runs.requestCancelAtomically(id);
                locked.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("cancel barrier timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                return null;
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var retry = pool.submit(() -> {
                attempting.countDown();
                return allocator.startAttempt(id, "node", "wait", 1);
            });
            assertThat(attempting.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            cancel.get(5, TimeUnit.SECONDS);
            assertThat(retry.get(5, TimeUnit.SECONDS)).isNull();
            assertThat(nodes.findByRunId(id)).extracting(WorkflowNodeRun::getAttempt).containsExactly(1);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void lateSuccessAfterCancellationIsClosedWithoutClaimingExternalCancellation() throws Exception {
        String id = "allocator-late-success";
        run(id);
        AttemptAllocator allocator = new AttemptAllocator(nodes, runs, transactions);
        WorkflowNodeRun attempt = allocator.startAttempt(id, "node", "wait", 0);
        // 模拟调用方持有旧快照，而恢复后的 running 行已有审批绑定。
        WorkflowNodeRun persisted = nodes.findById(attempt.getId()).orElseThrow();
        persisted.setApprovalId(123L);
        nodes.saveAndFlush(persisted);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var cancel = pool.submit(() -> new TransactionTemplate(transactions).execute(tx -> {
                runs.lockById(id).orElseThrow();
                runs.requestCancelAtomically(id);
                locked.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("cancel barrier timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                return null;
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var finish = pool.submit(() -> allocator.finishAttempt(attempt, AttemptAllocator.AttemptResult.builder()
                    .status("succeeded").outputJson("{\"ok\":true}").cancelDelivered(true).build()));
            release.countDown();
            cancel.get(5, TimeUnit.SECONDS);
            assertThat(finish.get(5, TimeUnit.SECONDS)).isZero();
            WorkflowNodeRun closed = nodes.findById(attempt.getId()).orElseThrow();
            assertThat(closed.getStatus()).isEqualTo("cancelled");
            assertThat(closed.getOutputJson()).isNull();
            assertThat(closed.getApprovalId()).isEqualTo(123L);
            assertThat(closed.getCancelDelivered()).isFalse();
            assertThat(allocator.finishAttempt(attempt, AttemptAllocator.AttemptResult.builder()
                    .status("succeeded").build())).isZero();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void staleNotificationOnlyFollowsSuccessfulCommitAndOnlyOnce() {
        String id = "allocator-stale-event";
        WorkflowRun run = run(id);
        AttemptAllocator allocator = new AttemptAllocator(nodes, runs, transactions);
        WorkflowNodeRun attempt = allocator.startAttempt(id, "node", "wait", 0);
        run.setStatus("timeout");
        runs.saveAndFlush(run);
        var events = new ArrayList<String>();
        var result = AttemptAllocator.AttemptResult.builder().status("succeeded").outputJson("secret").build();

        PlatformTransactionManager rollbackAtCommit = new PlatformTransactionManager() {
            @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
                return transactions.getTransaction(definition);
            }
            @Override public void commit(TransactionStatus status) {
                transactions.rollback(status);
            }
            @Override public void rollback(TransactionStatus status) {
                transactions.rollback(status);
            }
        };
        new AttemptAllocator(nodes, runs, rollbackAtCommit).finishAttempt(attempt, result, events::add);
        assertThat(events).isEmpty();
        assertThat(nodes.findById(attempt.getId()).orElseThrow().getStatus()).isEqualTo("running");

        assertThat(allocator.finishAttempt(attempt, result, events::add)).isZero();
        assertThat(events).containsExactly("timeout");
        assertThat(nodes.findById(attempt.getId()).orElseThrow().getOutputJson()).isNull();
        allocator.finishAttempt(attempt, result, events::add);
        assertThat(events).containsExactly("timeout");
    }

    @Test
    void normalFinishRetainsExistingApprovalSemantics() {
        String id = "allocator-normal-approval";
        run(id);
        AttemptAllocator allocator = new AttemptAllocator(nodes, runs, transactions);
        WorkflowNodeRun attempt = allocator.startAttempt(id, "node", "wait", 0);
        WorkflowNodeRun persisted = nodes.findById(attempt.getId()).orElseThrow();
        persisted.setApprovalId(123L);
        nodes.saveAndFlush(persisted);

        assertThat(allocator.finishAttempt(attempt, AttemptAllocator.AttemptResult.builder()
                .status("succeeded").build())).isEqualTo(1);
        assertThat(nodes.findById(attempt.getId()).orElseThrow().getApprovalId()).isNull();
    }

    @Test
    void simultaneousExecutorsOnlyAllocateOnceAndCannotOverwriteTerminalRows() throws Exception {
        String id = "allocator-double";
        run(id);
        AttemptAllocator allocator = new AttemptAllocator(nodes, runs, transactions);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<WorkflowNodeRun> action = () -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("start barrier timed out");
                return allocator.startAttempt(id, "node", "wait", 0);
            };
            var a = pool.submit(action);
            var b = pool.submit(action);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            WorkflowNodeRun first = a.get(5, TimeUnit.SECONDS);
            WorkflowNodeRun second = b.get(5, TimeUnit.SECONDS);
            assertThat((first == null) != (second == null)).isTrue();
            WorkflowNodeRun winner = first == null ? second : first;
            allocator.finishAttempt(winner, AttemptAllocator.AttemptResult.builder().status("succeeded").build());
            assertThat(allocator.startAttempt(id, "node", "wait", 1)).isNull();
            assertThat(nodes.findByRunId(id)).hasSize(1).first()
                    .extracting(WorkflowNodeRun::getStatus).isEqualTo("succeeded");
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }
}
