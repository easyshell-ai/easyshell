package com.easyshell.server.workflow.gate;

import com.easyshell.server.config.WorkflowSchemaConsistencyChecker;
import com.easyshell.server.model.entity.WorkflowApproval;
import com.easyshell.server.model.entity.WorkflowDispatchApprovalBinding;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises the real Flyway schema and JPA mapping, without enabling dispatch. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class WorkflowDispatchApprovalBindingTest {
    @Autowired private EntityManager entityManager;
    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void legacyApprovalHasNoBindingAndEvidenceRoundTrips() {
        WorkflowApproval approval = new WorkflowApproval();
        approval.setRunId("run-binding-test");
        approval.setNodeKey("gate");
        approval.setNodeType("approval");
        approval.setTitle("binding test");
        entityManager.persist(approval);
        entityManager.flush();
        Long id = approval.getId();
        assertThat(entityManager.find(WorkflowDispatchApprovalBinding.class, id)).isNull();

        WorkflowDispatchApprovalBinding binding = new WorkflowDispatchApprovalBinding();
        binding.setApprovalId(id);
        binding.setDispatchNodeRunId("node-run");
        binding.setDispatchAttempt(1);
        binding.setDispatchUserId(7L);
        binding.setDispatchScriptHmac("a".repeat(64));
        binding.setDispatchIntentHmac("b".repeat(64));
        binding.setDispatchTargets("[{\"agentId\":\"agent\",\"generation\":1}]");
        binding.setDispatchTimeoutSec(30);
        entityManager.persist(binding);
        entityManager.flush();
        entityManager.clear();

        WorkflowDispatchApprovalBinding loaded = entityManager.find(WorkflowDispatchApprovalBinding.class, id);
        assertThat(loaded.getDispatchScriptHmac()).isEqualTo("a".repeat(64));
        assertThat(loaded.getDispatchIntentHmac()).isEqualTo("b".repeat(64));
        assertThat(loaded.getDispatchTargets()).isEqualTo(binding.getDispatchTargets());
        assertThat(loaded.getDispatchConsumedAt()).isNull();
        assertThat(loaded.getDispatchTaskId()).isNull();
        assertThat(new WorkflowSchemaConsistencyChecker(dataSource, true).verify()).isEmpty();

        assertThatThrownBy(() -> jdbc.update("INSERT INTO workflow_dispatch_approval_binding "
                        + "SELECT * FROM workflow_dispatch_approval_binding WHERE approval_id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
