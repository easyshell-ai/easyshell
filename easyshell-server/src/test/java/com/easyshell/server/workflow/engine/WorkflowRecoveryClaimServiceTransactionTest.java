package com.easyshell.server.workflow.engine;

import com.easyshell.server.model.entity.WorkflowApproval;
import com.easyshell.server.model.entity.WorkflowNodeRun;
import com.easyshell.server.model.entity.WorkflowRun;
import com.easyshell.server.repository.WorkflowApprovalRepository;
import com.easyshell.server.repository.WorkflowNodeRunRepository;
import com.easyshell.server.repository.WorkflowRecoveryClaimRepository.Claim;
import com.easyshell.server.repository.WorkflowRecoveryClaimRepository.State;
import com.easyshell.server.repository.WorkflowRunRepository;
import com.easyshell.server.workflow.properties.WorkflowProperties;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real JPA transaction manager, JDBC claim writes and Flyway on one uniquely named H2 database. */
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=none", "spring.jpa.show-sql=false",
        "spring.flyway.enabled=true"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({WorkflowRecoveryClaimService.class, WorkflowRecoveryClaimServiceTransactionTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WorkflowRecoveryClaimServiceTransactionTest {
    @TestConfiguration
    static class Config {
        @Bean
        WorkflowProperties workflowProperties() {
            WorkflowProperties properties = new WorkflowProperties();
            properties.getEngine().setDurableResumeEnabled(true);
            return properties;
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = "jdbc:h2:mem:recovery_service_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
    }

    @Autowired private WorkflowRecoveryClaimService service;
    @Autowired private WorkflowRunRepository runs;
    @Autowired private WorkflowNodeRunRepository nodes;
    @Autowired private WorkflowApprovalRepository approvals;
    @Autowired private WorkflowProperties properties;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void mandatoryAndDefaultGateRejectWithoutWriting() {
        assertThat(new WorkflowProperties().getEngine().isDurableResumeEnabled()).isFalse();
        WorkflowNodeRun row = fixture();
        assertThatThrownBy(() -> service.claimWaiting(row.getRunId(), List.of(row)))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        properties.getEngine().setDurableResumeEnabled(false);
        try {
            assertThatThrownBy(() -> tx().executeWithoutResult(status ->
                    service.claimWaiting(row.getRunId(), List.of(row))))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("disabled");
        } finally {
            properties.getEngine().setDurableResumeEnabled(true);
        }
        assertThat(countClaims(row.getRunId())).isZero();
        assertThat(nodes.findById(row.getId()).orElseThrow().getStatus()).isEqualTo("waiting");
    }

    @Test
    void jpaAndJdbcUseSameDatabaseSessionAndClaimRollsBackWithResume() {
        WorkflowNodeRun row = fixture();
        tx().executeWithoutResult(status -> {
            long jpaSession = entityManager.unwrap(Session.class).doReturningWork(
                    WorkflowRecoveryClaimServiceTransactionTest::sessionId);
            Long jdbcSession = jdbc.execute((ConnectionCallback<Long>)
                    WorkflowRecoveryClaimServiceTransactionTest::sessionId);
            assertThat(jdbcSession).isEqualTo(jpaSession);
            RecoveryAttemptRef ref = service.claimWaiting(row.getRunId(), List.of(row)).get(row.getNodeKey());
            assertThat(ref).isNotNull();
            assertThat(ref.toString()).doesNotContain(ref.claim().token());
            assertThat(nodes.findById(row.getId()).orElseThrow().getStatus()).isEqualTo("running");
            assertThat(countClaims(row.getRunId())).isEqualTo(1);
            status.setRollbackOnly();
        });
        assertThat(countClaims(row.getRunId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workflow_recovery_run WHERE run_id=?",
                Long.class, row.getRunId())).isZero();
        assertThat(nodes.findById(row.getId()).orElseThrow().getStatus()).isEqualTo("waiting");
        assertThat(runs.findById(row.getRunId()).orElseThrow().getStatus()).isEqualTo("waiting");
        RecoveryAttemptRef ref = claim(row);
        assertThat(ref.claim().round()).isEqualTo(1);
        assertThat(countClaims(row.getRunId())).isEqualTo(1);
        assertThat(nodes.findById(row.getId()).orElseThrow().getStatus()).isEqualTo("running");
        assertThatThrownBy(() -> claim(row)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void expiredDeliveryCannotStartButKnownCompletionSharesNodeResultTransaction() {
        WorkflowNodeRun row = fixture();
        RecoveryAttemptRef ref = claim(row);
        tx().executeWithoutResult(status -> {
            assertThat(service.verifyEntered(ref)).isFalse();
            assertThat(service.enter(ref)).isTrue();
        });
        tx().executeWithoutResult(status -> {
            assertThat(service.verifyEntered(ref)).isTrue();
            assertThat(service.verifyEntered(ref)).isTrue();
        });
        jdbc.update("UPDATE workflow_recovery_batch SET expires_at=TIMESTAMP '2000-01-01 00:00:00' "
                + "WHERE run_id=?", row.getRunId());
        RecoveryAttemptRef stale = new RecoveryAttemptRef(new Claim(ref.claim().runId(), ref.claim().round(),
                ref.claim().owner(), UUID.randomUUID().toString()), ref.member());
        tx().executeWithoutResult(status -> {
            assertThat(service.verifyEntered(ref)).isFalse();
            assertThat(service.enter(ref)).isFalse();
            assertThat(service.finish(stale, State.SUCCEEDED)).isFalse();
            assertThat(service.abortUnentered(ref)).isFalse();
            WorkflowNodeRun actual = nodes.findById(row.getId()).orElseThrow();
            actual.setStatus("succeeded");
            actual.setOutputJson("{\"result\":true}");
            nodes.saveAndFlush(actual);
            assertThat(service.finish(ref, State.SUCCEEDED)).isTrue();
            status.setRollbackOnly();
        });
        assertThat(nodes.findById(row.getId()).orElseThrow().getStatus()).isEqualTo("running");
        assertThat(memberState(row)).isEqualTo("ENTERED");
        tx().executeWithoutResult(status -> {
            WorkflowNodeRun actual = nodes.findById(row.getId()).orElseThrow();
            actual.setStatus("succeeded");
            nodes.saveAndFlush(actual);
            assertThat(service.finish(ref, State.SUCCEEDED)).isTrue();
        });
        assertThat(nodes.findById(row.getId()).orElseThrow().getStatus()).isEqualTo("succeeded");
        assertThat(memberState(row)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT active_round FROM workflow_recovery_run WHERE run_id=?",
                Long.class, row.getRunId())).isNull();
        tx().executeWithoutResult(status -> {
            assertThat(service.finish(stale, State.FAILED)).isFalse();
            assertThat(service.finish(ref, State.FAILED)).isFalse();
        });
    }

    @Test
    void uncertaintyCannotBeResolvedByOrdinaryCompletionOrRejection() {
        WorkflowNodeRun row = fixture();
        RecoveryAttemptRef ref = claim(row);
        tx().executeWithoutResult(status -> assertThat(service.enter(ref)).isTrue());
        tx().executeWithoutResult(status -> assertThat(service.finish(ref, State.UNCERTAIN)).isTrue());
        tx().executeWithoutResult(status -> {
            assertThat(service.finish(ref, State.SUCCEEDED)).isFalse();
            assertThat(service.rejectEntered(ref)).isFalse();
            assertThat(service.abortUnentered(ref)).isFalse();
            assertThat(service.verifyEntered(ref)).isFalse();
        });
        assertThat(memberState(row)).isEqualTo("UNCERTAIN");
        assertThat(jdbc.queryForObject("SELECT active_round FROM workflow_recovery_run WHERE run_id=?",
                Long.class, row.getRunId())).isEqualTo(ref.claim().round());
    }

    @Test
    void onlyClaimedCanAbortAndEnteredRequiresSeparateRejectionApi() {
        WorkflowNodeRun unentered = fixture();
        RecoveryAttemptRef first = claim(unentered);
        tx().executeWithoutResult(status -> {
            assertThat(service.rejectEntered(first)).isFalse();
            assertThat(service.abortUnentered(first)).isTrue();
        });
        assertThat(memberState(unentered)).isEqualTo("CANCELLED");
        WorkflowNodeRun entered = fixture();
        RecoveryAttemptRef second = claim(entered);
        tx().executeWithoutResult(status -> assertThat(service.enter(second)).isTrue());
        tx().executeWithoutResult(status -> {
            assertThat(service.abortUnentered(second)).isFalse();
            // Fixture supplies the standard-executor rejection proof; service does not fabricate it.
            assertThat(service.rejectEntered(second)).isTrue();
        });
        assertThat(memberState(entered)).isEqualTo("CANCELLED");
    }

    @Test
    void pendingDecisionAndHigherAttemptRejectWithoutPersistentClaim() {
        WorkflowNodeRun pending = fixture();
        jdbc.update("UPDATE workflow_approval SET status='pending' WHERE id=?", pending.getApprovalId());
        assertThatThrownBy(() -> claim(pending)).isInstanceOf(IllegalStateException.class);
        assertThat(countClaims(pending.getRunId())).isZero();
        WorkflowNodeRun older = fixture();
        tx().executeWithoutResult(status -> {
            WorkflowNodeRun newer = new WorkflowNodeRun();
            newer.setId(UUID.randomUUID().toString());
            newer.setRunId(older.getRunId());
            newer.setNodeKey(older.getNodeKey());
            newer.setNodeType("approval");
            newer.setAttempt(2);
            newer.setStatus("succeeded");
            nodes.saveAndFlush(newer);
        });
        assertThatThrownBy(() -> claim(older)).isInstanceOf(IllegalStateException.class);
        assertThat(countClaims(older.getRunId())).isZero();
        assertThat(nodes.findById(older.getId()).orElseThrow().getStatus()).isEqualTo("waiting");
    }

    private RecoveryAttemptRef claim(WorkflowNodeRun row) {
        return tx().execute(status -> service.claimWaiting(row.getRunId(), List.of(row)).get(row.getNodeKey()));
    }

    private WorkflowNodeRun fixture() {
        return tx().execute(status -> {
            WorkflowRun run = new WorkflowRun();
            run.setId(UUID.randomUUID().toString());
            run.setWorkflowId(1L);
            run.setVersionId(1L);
            run.setVersionNo(1);
            run.setStatus("waiting");
            runs.saveAndFlush(run);
            WorkflowApproval approval = new WorkflowApproval();
            approval.setRunId(run.getId());
            approval.setNodeKey("approval");
            approval.setNodeType("approval");
            approval.setTitle("Recovery transaction proof");
            approval.setStatus("approved");
            // A non-null @Version may make save use merge: only its returned instance owns the generated id.
            approval = approvals.saveAndFlush(approval);
            assertThat(approval.getId()).isNotNull();
            entityManager.refresh(approval);
            assertThat(approval.getRunId()).isEqualTo(run.getId());
            assertThat(approval.getNodeKey()).isEqualTo("approval");
            assertThat(approval.getNodeType()).isEqualTo("approval");
            assertThat(approval.getStatus()).isEqualTo("approved");
            WorkflowNodeRun node = new WorkflowNodeRun();
            node.setId(UUID.randomUUID().toString());
            node.setRunId(run.getId());
            node.setNodeKey("approval");
            node.setNodeType("approval");
            node.setAttempt(1);
            node.setStatus("waiting");
            node.setApprovalId(approval.getId());
            WorkflowNodeRun saved = nodes.saveAndFlush(node);
            assertThat(saved.getApprovalId()).isEqualTo(approval.getId());
            return saved;
        });
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private long countClaims(String runId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM workflow_recovery_member WHERE run_id=?", Long.class, runId);
    }

    private String memberState(WorkflowNodeRun row) {
        return jdbc.queryForObject("SELECT claim_state FROM workflow_recovery_member WHERE node_run_id=? "
                + "AND attempt=?", String.class, row.getId(), row.getAttempt());
    }

    private static long sessionId(Connection connection) throws java.sql.SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT SESSION_ID()")) {
            assertThat(rows.next()).isTrue();
            return rows.getLong(1);
        }
    }
}
