package com.easyshell.server.workflow.gate;

import com.easyshell.server.model.entity.Task;
import com.easyshell.server.model.entity.WorkflowApproval;
import com.easyshell.server.repository.TaskRepository;
import com.easyshell.server.repository.WorkflowApprovalRepository;
import com.easyshell.server.repository.WorkflowDispatchApprovalBindingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Proves that JPA task writes and JDBC binding consumption share one Spring transaction. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:workflow_dispatch_binding_tx;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL",
        "spring.jpa.show-sql=false"
})
@Import(WorkflowDispatchApprovalBindingRepository.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WorkflowDispatchApprovalBindingTransactionTest {
    private static final String SCRIPT_HMAC = "a".repeat(64);
    private static final String INTENT_HMAC = "b".repeat(64);
    private static final String TARGETS = "[{\"agentId\":\"agent-tx\",\"generation\":1}]";

    @Autowired private WorkflowDispatchApprovalBindingRepository bindings;
    @Autowired private WorkflowApprovalRepository approvals;
    @Autowired private TaskRepository tasks;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void jdbcConsumeAndJpaTaskBothRollbackAndCommitTogether() {
        long approvalId = createApprovedBinding();
        new TransactionTemplate(transactionManager).execute(status -> {
            assertThat(bindings.consume(approvalId, "run-tx", "node-run-tx", 1, 42,
                    SCRIPT_HMAC, INTENT_HMAC, TARGETS, 30, "task-tx-rollback")).isEqualTo(1);
            tasks.saveAndFlush(task("task-tx-rollback"));
            status.setRollbackOnly();
            return null;
        });

        assertThat(tasks.findById("task-tx-rollback")).isEmpty();
        assertThat(consumedAt(approvalId)).isNull();
        assertThat(dispatchTaskId(approvalId)).isNull();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(bindings.consume(approvalId, "run-tx", "node-run-tx", 1, 42,
                    SCRIPT_HMAC, INTENT_HMAC, TARGETS, 30, "task-tx-rollback")).isEqualTo(1);
            tasks.saveAndFlush(task("task-tx-rollback"));
        });

        assertThat(tasks.findById("task-tx-rollback")).isPresent();
        assertThat(consumedAt(approvalId)).isNotNull();
        assertThat(dispatchTaskId(approvalId)).isEqualTo("task-tx-rollback");
    }

    @Test
    void mandatoryBindingConsumptionRejectsCallsWithoutAnOuterTransaction() {
        long approvalId = createApprovedBinding();

        assertThatThrownBy(() -> bindings.consume(approvalId, "run-tx", "node-run-tx", 1, 42,
                SCRIPT_HMAC, INTENT_HMAC, TARGETS, 30, "task-no-tx"))
                .isInstanceOf(RuntimeException.class);

        assertThat(tasks.findById("task-no-tx")).isEmpty();
        assertThat(consumedAt(approvalId)).isNull();
        assertThat(dispatchTaskId(approvalId)).isNull();
    }

    private long createApprovedBinding() {
        return new TransactionTemplate(transactionManager).execute(status -> {
            WorkflowApproval approval = new WorkflowApproval();
            approval.setRunId("run-tx");
            approval.setNodeKey("script");
            approval.setNodeType("script-exec");
            approval.setTitle("transaction test");
            approval.setExpiresAt(LocalDateTime.now().plusHours(1));
            approval = approvals.saveAndFlush(approval);
            assertThat(bindings.create(approval.getId(), "run-tx", "node-run-tx", 1, 42,
                    SCRIPT_HMAC, INTENT_HMAC, TARGETS, 30)).isEqualTo(1);
            jdbc.update("UPDATE workflow_approval SET status='approved', decided_by=99, " +
                    "decided_at=CURRENT_TIMESTAMP WHERE id=?", approval.getId());
            return approval.getId();
        });
    }

    private Task task(String id) {
        Task task = new Task();
        task.setId(id);
        task.setName("transaction test");
        task.setScriptContent("echo safe");
        task.setCreatedBy(42L);
        task.setStatus(1);
        task.setTotalCount(1);
        task.setSuccessCount(0);
        task.setFailedCount(0);
        return task;
    }

    private LocalDateTime consumedAt(long approvalId) {
        return jdbc.queryForObject("SELECT dispatch_consumed_at FROM workflow_dispatch_approval_binding " +
                "WHERE approval_id=?", LocalDateTime.class, approvalId);
    }

    private String dispatchTaskId(long approvalId) {
        return jdbc.queryForObject("SELECT dispatch_task_id FROM workflow_dispatch_approval_binding " +
                "WHERE approval_id=?", String.class, approvalId);
    }
}
