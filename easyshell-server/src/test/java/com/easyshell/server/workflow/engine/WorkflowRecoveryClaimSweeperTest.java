package com.easyshell.server.workflow.engine;

import com.easyshell.server.repository.WorkflowRecoveryClaimRepository.State;

import com.easyshell.server.repository.WorkflowNodeRunRepository;
import com.easyshell.server.repository.WorkflowRecoveryClaimRepository;
import com.easyshell.server.repository.WorkflowRecoveryClaimRepository.Member;
import com.easyshell.server.repository.WorkflowRunRepository;
import com.easyshell.server.service.impl.AuditLogServiceImpl;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.workflow.properties.WorkflowProperties;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/** Real Flyway/JPA/JDBC and existing audit service, isolated H2; no executor, Task dispatch or external effects. */
@DataJpaTest(properties = {"spring.jpa.show-sql=false", "spring.flyway.enabled=true"})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({WorkflowRecoveryClaimService.class, AuditLogServiceImpl.class, WorkflowRecoveryClaimSweeperTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class WorkflowRecoveryClaimSweeperTest {
    @TestConfiguration
    static class Config {
        @Bean WorkflowProperties workflowProperties() {
            WorkflowProperties properties = new WorkflowProperties();
            properties.getEngine().setDurableResumeEnabled(true);
            properties.getEngine().setAutomaticRecoveryEnabled(false);
            return properties;
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = "jdbc:h2:mem:claim_sweeper_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=10000";
        registry.add("spring.datasource.url", () -> url);
    }

    @Autowired private WorkflowRecoveryClaimService service;
    @Autowired private WorkflowProperties properties;
    @Autowired private WorkflowRunRepository runs;
    @Autowired private WorkflowNodeRunRepository nodes;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @SpyBean private AuditLogService audit;
    private final WorkflowRecoveryClaimRepository repository = new WorkflowRecoveryClaimRepository(true);

    @Test
    void restartedServiceUsesPersistedIdentityAndMixedBatchNeverTouchesEnteredNodeOrTask() {
        String runId = id("mixed");
        List<RecoveryAttemptRef> refs = fixture(runId, 2, true);
        enter(refs.get(1));
        expire(runId);
        WorkflowRecoveryClaimService restarted = new WorkflowRecoveryClaimService(properties, runs, nodes, entityManager, jdbc);
        WorkflowRecoveryClaimSweeper scanner = scanner(restarted);
        assertThat(properties.getEngine().isAutomaticRecoveryEnabled()).isFalse();
        scanner.sweep();
        assertThat(nodeState(refs.get(0))).isEqualTo("cancelled");
        assertThat(jdbc.queryForObject("SELECT error_message FROM workflow_node_run WHERE id=?", String.class,
                refs.get(0).member().nodeRunId())).startsWith("resume_aborted:");
        assertThat(memberState(refs.get(0))).isEqualTo("CANCELLED");
        assertThat(nodeState(refs.get(1))).isEqualTo("running");
        assertThat(memberState(refs.get(1))).isEqualTo("UNCERTAIN");
        assertThat(jdbc.queryForObject("SELECT task_id FROM workflow_node_run WHERE id=?", String.class,
                refs.get(1).member().nodeRunId())).isEqualTo("sentinel-task");
        assertThat(runs.findById(runId).orElseThrow().getStatus()).isEqualTo("running");
        assertThat(service.hasActiveBatch(runId)).isTrue();
        assertThat(auditCount(runId)).isEqualTo(1);
        scanner.sweep();
        assertThat(auditCount(runId)).isEqualTo(1);
        assertThat(memberState(refs.get(1))).isEqualTo("UNCERTAIN");
        assertThat(tx().<List<String>>execute(status -> service.findExpiredRunIds(null, 100))).doesNotContain(runId);
    }

    @Test
    void freshLeaseAndDisabledDurableRemainUntouchedThenExpiredClaimClosesExactlyOnce() {
        String runId = id("lease");
        RecoveryAttemptRef ref = fixture(runId, 1, false).get(0);
        WorkflowRecoveryClaimSweeper scanner = scanner(service);
        scanner.sweep();
        assertThat(memberState(ref)).isEqualTo("CLAIMED");
        assertThat(nodeState(ref)).isEqualTo("running");
        assertThat(auditCount(runId)).isZero();
        expire(runId);
        properties.getEngine().setDurableResumeEnabled(false);
        try {
            scanner.sweep();
            assertThat(memberState(ref)).isEqualTo("CLAIMED");
            assertThatThrownBy(() -> service.hasActiveBatch(runId)).isInstanceOf(IllegalStateException.class);
        } finally {
            properties.getEngine().setDurableResumeEnabled(true);
        }
        scanner.sweep();
        scanner.sweep();
        assertThat(memberState(ref)).isEqualTo("CANCELLED");
        assertThat(service.hasActiveBatch(runId)).isFalse();
        assertThat(auditCount(runId)).isEqualTo(1);
        assertThat(service.hasActiveBatch(id("absent"))).isFalse();
    }

    @Test
    void auditFailureRollsBackBothNodeAndClaimAndNextScannerCanRetry() {
        String runId = id("audit-fail");
        RecoveryAttemptRef ref = fixture(runId, 1, false).get(0);
        expire(runId);
        AuditLogService auditTarget = AopTestUtils.getUltimateTargetObject(audit);
        doThrow(new IllegalStateException("synthetic audit failure"))
                .when(auditTarget).logSync(any(), eq("SYSTEM"), eq("WORKFLOW_RECOVERY_CLAIM_EXPIRED"),
                        eq("workflow_run"), eq(runId), any(), any(), eq("success"));
        try {
            scanner(service).sweep();
        } finally {
            reset(auditTarget);
        }
        assertThat(memberState(ref)).isEqualTo("CLAIMED");
        assertThat(nodeState(ref)).isEqualTo("running");
        assertThat(service.hasActiveBatch(runId)).isTrue();
        assertThat(auditCount(runId)).isZero();
        scanner(service).sweep();
        assertThat(nodeState(ref)).isEqualTo("cancelled");
        assertThat(auditCount(runId)).isEqualTo(1);
    }

    @Test
    void exactNodeConflictRollsBackEarlierMembersInsteadOfPartiallyExpiringBatch() {
        String runId = id("conflict");
        List<RecoveryAttemptRef> refs = fixture(runId, 2, false);
        expire(runId);
        jdbc.update("UPDATE workflow_node_run SET status='succeeded' WHERE id=?", refs.get(1).member().nodeRunId());
        scanner(service).sweep();
        assertThat(memberState(refs.get(0))).isEqualTo("CLAIMED");
        assertThat(memberState(refs.get(1))).isEqualTo("CLAIMED");
        assertThat(nodeState(refs.get(0))).isEqualTo("running");
        assertThat(nodeState(refs.get(1))).isEqualTo("succeeded");
        assertThat(auditCount(runId)).isZero();
    }

    @Test
    void independentScannersCompeteWithoutDuplicateAuditOrOwnerTakeover() throws Exception {
        String runId = id("race");
        RecoveryAttemptRef ref = fixture(runId, 1, false).get(0);
        expire(runId);
        var executor = Executors.newFixedThreadPool(3);
        CountDownLatch ready = new CountDownLatch(3);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) futures.add(executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                scanner(service).sweep();
                return null;
            }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) future.get(30, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(memberState(ref)).isEqualTo("CANCELLED");
        assertThat(nodeState(ref)).isEqualTo("cancelled");
        assertThat(auditCount(runId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT owner_id FROM workflow_recovery_batch WHERE run_id=?", String.class,
                runId)).isEqualTo(ref.claim().owner());
    }

    @Test
    void cursorPassesOneHundredConflictingCandidatesAndUnknownOnlyDoesNotStarveLaterWork() {
        String prefix = id("fair");
        for (int i = 0; i < 100; i++) {
            String runId = prefix + String.format("-%03d", i);
            RecoveryAttemptRef ref = fixture(runId, 1, false).get(0);
            expire(runId);
            jdbc.update("UPDATE workflow_node_run SET status='succeeded' WHERE id=?", ref.member().nodeRunId());
        }
        String unknownId = prefix + "-100";
        RecoveryAttemptRef unknown = fixture(unknownId, 1, false).get(0);
        enter(unknown);
        tx().executeWithoutResult(status -> assertThat(service.finish(unknown, State.UNCERTAIN)).isTrue());
        expire(unknownId);
        String healthyId = prefix + "-101";
        RecoveryAttemptRef healthy = fixture(healthyId, 1, false).get(0);
        expire(healthyId);
        WorkflowRecoveryClaimSweeper scanner = scanner(service);
        scanner.sweep();
        assertThat(memberState(healthy)).isEqualTo("CLAIMED");
        scanner.sweep();
        assertThat(memberState(healthy)).isEqualTo("CANCELLED");
        assertThat(auditCount(healthyId)).isEqualTo(1);
        assertThat(memberState(unknown)).isEqualTo("UNCERTAIN");
        assertThatThrownBy(() -> tx().execute(status -> service.findExpiredRunIds(null, 101)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private List<RecoveryAttemptRef> fixture(String runId, int count, boolean boundTask) {
        return tx().execute(status -> {
            jdbc.update("INSERT INTO workflow_run (id,workflow_id,version_id,version_no,trigger_type,status,"
                    + "variables_truncated,cancel_requested) VALUES (?,1,1,1,'manual','running',0,0)", runId);
            List<Member> members = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                String nodeId = runId + "-n" + i;
                jdbc.update("INSERT INTO workflow_node_run (id,run_id,node_key,node_type,attempt,status,"
                                + "cancel_delivered,task_id) VALUES (?,?,?,'approval',1,'running',0,?)",
                        nodeId, runId, "node-" + i, boundTask && i == 1 ? "sentinel-task" : null);
                members.add(new Member(nodeId, "node-" + i, 1));
            }
            return jdbc.execute((ConnectionCallback<List<RecoveryAttemptRef>>) connection -> {
                repository.provision(connection, runId);
                var claim = repository.claim(connection, runId, "persisted-old-process", Duration.ofHours(1), members)
                        .orElseThrow();
                return members.stream().map(member -> new RecoveryAttemptRef(claim, member)).toList();
            });
        });
    }

    private void enter(RecoveryAttemptRef ref) {
        tx().executeWithoutResult(status -> assertThat(service.enter(ref)).isTrue());
    }

    private void expire(String runId) {
        jdbc.update("UPDATE workflow_recovery_batch SET expires_at=TIMESTAMP '2000-01-01 00:00:00' WHERE run_id=?", runId);
    }

    private WorkflowRecoveryClaimSweeper scanner(WorkflowRecoveryClaimService claimService) {
        return new WorkflowRecoveryClaimSweeper(claimService, properties, audit, transactionManager);
    }

    private TransactionTemplate tx() { return new TransactionTemplate(transactionManager); }
    private String id(String prefix) { return prefix + "-" + UUID.randomUUID().toString().replace("-", ""); }
    private String nodeState(RecoveryAttemptRef ref) {
        return jdbc.queryForObject("SELECT status FROM workflow_node_run WHERE id=?", String.class, ref.member().nodeRunId());
    }
    private String memberState(RecoveryAttemptRef ref) {
        return jdbc.queryForObject("SELECT claim_state FROM workflow_recovery_member WHERE node_run_id=? AND attempt=?",
                String.class, ref.member().nodeRunId(), ref.member().attempt());
    }
    private long auditCount(String runId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE resource_id=? "
                + "AND action='WORKFLOW_RECOVERY_CLAIM_EXPIRED'", Long.class, runId);
    }
}
