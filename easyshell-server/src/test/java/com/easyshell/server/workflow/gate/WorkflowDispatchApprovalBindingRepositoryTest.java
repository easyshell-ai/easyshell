package com.easyshell.server.workflow.gate;

import com.easyshell.server.model.entity.WorkflowApproval;
import com.easyshell.server.repository.WorkflowApprovalRepository;
import com.easyshell.server.repository.WorkflowDispatchApprovalBindingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(WorkflowDispatchApprovalBindingRepository.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WorkflowDispatchApprovalBindingRepositoryTest {
    @Autowired WorkflowDispatchApprovalBindingRepository bindings;
    @Autowired WorkflowApprovalRepository approvals;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;

    private final String script = "a".repeat(64);
    private final String intent = "b".repeat(64);
    private final String targets = "[{\"agentId\":\"a\",\"generation\":1}]";

    private long setup() {
        return new TransactionTemplate(manager).execute(status -> {
            WorkflowApproval a = new WorkflowApproval();
            a.setRunId("run"); a.setNodeKey("gate"); a.setNodeType("script-exec"); a.setTitle("test");
            a.setExpiresAt(LocalDateTime.now().plusHours(1));
            a = approvals.saveAndFlush(a);
            assertThat(bindings.create(a.getId(), "run", "node", 1, 7, script, intent, targets, 30)).isEqualTo(1);
            jdbc.update("UPDATE workflow_approval SET status='approved', decided_by=9, decided_at=CURRENT_TIMESTAMP WHERE id=?", a.getId());
            return a.getId();
        });
    }

    @Test
    void ordinaryApprovalCannotCreateOrConsumeDispatchBinding() {
        long id = setup();
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            jdbc.update("UPDATE workflow_approval SET node_type='approval' WHERE id=?", id);
            assertThat(consume(id, "node", 1, 7, intent, targets, 30)).isZero();
            WorkflowApproval ordinary = new WorkflowApproval();
            ordinary.setRunId("run"); ordinary.setNodeKey("other");
            ordinary.setNodeType("approval"); ordinary.setTitle("test");
            ordinary.setExpiresAt(LocalDateTime.now().plusHours(1));
            ordinary = approvals.saveAndFlush(ordinary);
            assertThat(bindings.create(ordinary.getId(), "run", "node", 1, 7,
                    script, intent, targets, 30)).isZero();
        });
    }

    private int consume(long id, String node, int attempt, long user, String digest, String target, int timeout) {
        return bindings.consume(id, "run", node, attempt, user, script, digest, target, timeout, "task");
    }

    @Test
    void requiresTransactionAndRejectsTamperingAndExpiry() {
        long id = setup();
        assertThatThrownBy(() -> consume(id, "node", 1, 7, intent, targets, 30)).isInstanceOf(Exception.class);
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            assertThat(consume(id, "other", 1, 7, intent, targets, 30)).isZero();
            assertThat(consume(id, "node", 2, 7, intent, targets, 30)).isZero();
            assertThat(consume(id, "node", 1, 8, intent, targets, 30)).isZero();
            assertThat(consume(id, "node", 1, 7, "c".repeat(64), targets, 30)).isZero();
            assertThat(consume(id, "node", 1, 7, intent, targets.replace(":1", ":2"), 30)).isZero();
            assertThat(consume(id, "node", 1, 7, intent, targets, 31)).isZero();
            jdbc.update("UPDATE workflow_approval SET expires_at=? WHERE id=?", LocalDateTime.now().minusSeconds(1), id);
            assertThat(consume(id, "node", 1, 7, intent, targets, 30)).isZero();
        });
    }

    @Test
    void rollbackRestoresConsumability() {
        long id = setup();
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            assertThat(consume(id, "node", 1, 7, intent, targets, 30)).isEqualTo(1);
            tx.setRollbackOnly();
        });
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            assertThat(consume(id, "node", 1, 7, intent, targets, 30)).isEqualTo(1);
            assertThat(consume(id, "node", 1, 7, intent, targets, 30)).isZero();
        });
    }

    @Test
    void concurrentConsumersHaveExactlyOneWinner() throws Exception {
        long id = setup();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            var first = pool.submit(() -> { start.await(); return new TransactionTemplate(manager).execute(tx -> consume(id, "node", 1, 7, intent, targets, 30)); });
            var second = pool.submit(() -> { start.await(); return new TransactionTemplate(manager).execute(tx -> consume(id, "node", 1, 7, intent, targets, 30)); });
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT dispatch_task_id FROM workflow_dispatch_approval_binding WHERE approval_id=?", String.class, id)).isEqualTo("task");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void approvalFromAnotherBindingCannotAuthorizeThisOne() {
        long pendingId = setup();
        long approvedId = setup();
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            jdbc.update("UPDATE workflow_approval SET status='pending', decided_by=NULL, decided_at=NULL WHERE id=?", pendingId);
            assertThat(consume(pendingId, "node", 1, 7, intent, targets, 30)).isZero();
            assertThat(consume(approvedId, "node", 1, 7, intent, targets, 30)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT dispatch_consumed_at FROM workflow_dispatch_approval_binding WHERE approval_id=?",
                    LocalDateTime.class, pendingId)).isNull();
        });
    }
}
