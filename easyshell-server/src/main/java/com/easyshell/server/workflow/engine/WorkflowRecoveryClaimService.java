package com.easyshell.server.workflow.engine;

import com.easyshell.server.model.entity.WorkflowApproval;
import com.easyshell.server.model.entity.WorkflowNodeRun;
import com.easyshell.server.model.entity.WorkflowRun;
import com.easyshell.server.repository.WorkflowNodeRunRepository;
import com.easyshell.server.repository.WorkflowRecoveryClaimRepository;
import com.easyshell.server.repository.WorkflowRecoveryClaimRepository.Member;
import com.easyshell.server.repository.WorkflowRecoveryClaimRepository.State;
import com.easyshell.server.repository.WorkflowRunRepository;
import com.easyshell.server.workflow.properties.WorkflowProperties;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Joins the caller's JPA transaction using JdbcTemplate/DataSourceUtils; never commits or opens a transaction.
 * Caller must use the JpaTransactionManager and its matching DataSource. No reclaim or dispatch on expiration.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class WorkflowRecoveryClaimService {
    private final WorkflowRecoveryClaimRepository claims = new WorkflowRecoveryClaimRepository(true);
    private final String owner = "resume-" + UUID.randomUUID();
    private final WorkflowProperties properties;
    private final WorkflowRunRepository runs;
    private final WorkflowNodeRunRepository nodes;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbc;

    private record Expected(String nodeKey, String nodeType, Integer attempt, Long approvalId) { }

    public record SweepResult(int cancelled, int uncertain, boolean batchClosed) {
        public boolean changed() { return cancelled != 0 || uncertain != 0 || batchClosed; }
    }

    /** Filtering hint only. Errors propagate: scanner must reject that candidate, never assume absence. */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRED)
    public boolean hasActiveBatch(String runId) {
        gate();
        if (runId == null || runId.isBlank() || runId.length() > 64) {
            throw new IllegalArgumentException("invalid run id");
        }
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT COUNT(*) > 0 FROM workflow_recovery_run "
                + "WHERE run_id = ? AND active_round IS NOT NULL", Boolean.class, runId));
    }

    public WorkflowRecoveryClaimService(WorkflowProperties properties, WorkflowRunRepository runs,
                                       WorkflowNodeRunRepository nodes, EntityManager entityManager,
                                       JdbcTemplate jdbc) {
        this.properties = properties;
        this.runs = runs;
        this.nodes = nodes;
        this.entityManager = entityManager;
        this.jdbc = jdbc;
    }

    /**
     * Reacquires the business run lock (safe if caller already holds it). All current waiting rows must match.
     * Returned refs MUST NOT be submitted until the outer transaction commits; unknown commit means no delivery.
     * Active batches and permanent member collisions fail the whole transaction, never silently replay.
     */
    public Map<String, RecoveryAttemptRef> claimWaiting(String runId, List<WorkflowNodeRun> expected) {
        gate();
        WorkflowRun run = lockRun(runId);
        if (!"waiting".equals(run.getStatus()) || !Boolean.FALSE.equals(run.getCancelRequested())) {
            throw new IllegalStateException("run is not a resumable wait");
        }
        expected = List.copyOf(expected);
        if (expected.isEmpty()) throw new IllegalArgumentException("empty recovery batch");
        Map<String, Expected> requested = new LinkedHashMap<>();
        for (WorkflowNodeRun row : expected) {
            Objects.requireNonNull(row);
            if (!runId.equals(row.getRunId()) || row.getId() == null
                    || requested.putIfAbsent(row.getId(), new Expected(row.getNodeKey(), row.getNodeType(),
                        row.getAttempt(), row.getApprovalId())) != null) {
                throw new IllegalArgumentException("invalid or duplicate recovery member");
            }
        }
        return jdbc.execute((ConnectionCallback<Map<String, RecoveryAttemptRef>>) connection -> {
            claims.provisionIfAbsent(connection, runId);
            if (claims.findActive(connection, runId).isPresent()) {
                throw new IllegalStateException("run already has an active recovery batch");
            }
            // Locking reads avoid an old RR snapshot; refresh also discards stale managed entity values.
            List<WorkflowNodeRun> all = entityManager.createQuery(
                            "select n from WorkflowNodeRun n where n.runId = :runId order by n.id", WorkflowNodeRun.class)
                    .setParameter("runId", runId).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
            for (WorkflowNodeRun row : all) entityManager.refresh(row, LockModeType.PESSIMISTIC_WRITE);
            List<WorkflowNodeRun> waiting = all.stream().filter(row -> "waiting".equals(row.getStatus())).toList();
            if (waiting.size() != requested.size()) throw new IllegalStateException("waiting set changed");
            Set<String> keys = new HashSet<>();
            for (WorkflowNodeRun row : waiting) {
                Expected supplied = requested.get(row.getId());
                if (supplied == null || !Objects.equals(supplied.nodeKey(), row.getNodeKey())
                        || !Objects.equals(supplied.nodeType(), row.getNodeType())
                        || !Objects.equals(supplied.attempt(), row.getAttempt())
                        || !Objects.equals(supplied.approvalId(), row.getApprovalId())
                        || !keys.add(row.getNodeKey()) || row.getAttempt() == null || row.getAttempt() < 1
                        || all.stream().anyMatch(other -> row.getNodeKey().equals(other.getNodeKey())
                            && !row.getId().equals(other.getId()) && other.getAttempt() >= row.getAttempt())) {
                    throw new IllegalStateException("recovery attempt is not exact and latest");
                }
                WorkflowApproval approval = row.getApprovalId() == null ? null
                        : entityManager.find(WorkflowApproval.class, row.getApprovalId(), LockModeType.PESSIMISTIC_WRITE);
                if (approval != null) entityManager.refresh(approval, LockModeType.PESSIMISTIC_WRITE);
                if (approval == null || !runId.equals(approval.getRunId())
                        || !row.getNodeKey().equals(approval.getNodeKey())
                        || !row.getNodeType().equals(approval.getNodeType())
                        || approval.getStatus() == null
                        || !Set.of("approved", "rejected", "timeout", "cancelled").contains(approval.getStatus())) {
                    throw new IllegalStateException("recovery approval is not a matching decision");
                }
            }
            var members = waiting.stream().map(row -> new Member(row.getId(), row.getNodeKey(), row.getAttempt())).toList();
            var claim = claims.claim(connection, runId, owner, Duration.ofHours(1), members).orElseThrow();
            if (runs.resumeIfWaiting(runId) != 1) throw new IllegalStateException("run resume conflict");
            Map<String, RecoveryAttemptRef> refs = new LinkedHashMap<>();
            for (WorkflowNodeRun row : waiting) {
                if (nodes.resumeWaitingAttempt(row.getId(), runId, row.getApprovalId()) != 1) {
                    throw new IllegalStateException("attempt resume conflict");
                }
                refs.put(row.getNodeKey(), new RecoveryAttemptRef(claim,
                        new Member(row.getId(), row.getNodeKey(), row.getAttempt())));
                entityManager.refresh(row);
            }
            entityManager.refresh(run);
            return Map.copyOf(refs);
        });
    }

    /** Commit the caller's transaction before submitting the member to an executor. */
    public boolean enter(RecoveryAttemptRef ref) {
        gate();
        WorkflowRun run = lockRun(ref.claim().runId());
        if (!"running".equals(run.getStatus()) || !Boolean.FALSE.equals(run.getCancelRequested())) return false;
        return jdbc.execute((ConnectionCallback<Boolean>) connection -> claims.enter(connection, ref.claim(), ref.member()));
    }

    /** Non-consuming identity/lease check immediately before execution; it does not renew or transfer ownership. */
    public boolean verifyEntered(RecoveryAttemptRef ref) {
        gate();
        WorkflowRun run = lockRun(ref.claim().runId());
        if (!"running".equals(run.getStatus()) || !Boolean.FALSE.equals(run.getCancelRequested())) return false;
        return jdbc.execute((ConnectionCallback<Boolean>) connection ->
                claims.verifyEntered(connection, ref.claim(), ref.member()));
    }

    /** Caller writes the node result in THIS transaction; false must abort any tentative result writes. */
    public boolean finish(RecoveryAttemptRef ref, State outcome) {
        gate();
        lockRun(ref.claim().runId());
        return jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            boolean changed = claims.complete(connection, ref.claim(), ref.member(), outcome);
            if (changed) claims.finishBatch(connection, ref.claim());
            return changed;
        });
    }

    /** Caller closes the exact node attempt with resume_aborted in the same transaction. */
    public boolean abortUnentered(RecoveryAttemptRef ref) {
        gate();
        lockRun(ref.claim().runId());
        return jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            boolean changed = claims.closeProvenUndelivered(connection, ref.claim(), ref.member());
            if (changed) claims.finishBatch(connection, ref.claim());
            return changed;
        });
    }

    /** Requires external proof of standard executor rejection BEFORE acceptance; this method cannot prove it. */
    public boolean rejectEntered(RecoveryAttemptRef ref) {
        gate();
        lockRun(ref.claim().runId());
        return jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            boolean changed = claims.rejectEntered(connection, ref.claim(), ref.member());
            if (changed) claims.finishBatch(connection, ref.claim());
            return changed;
        });
    }

    private WorkflowRun lockRun(String runId) {
        WorkflowRun run = runs.lockById(runId).orElseThrow(() -> new IllegalStateException("recovery run missing"));
        entityManager.refresh(run, LockModeType.PESSIMISTIC_WRITE);
        return run;
    }

    /** Discovery is not ownership and never locks claim rows before the business run. */
    public List<String> findExpiredRunIds(String afterRunId, int limit) {
        gate();
        return jdbc.execute((ConnectionCallback<List<String>>) connection ->
                claims.findExpiredRunIds(connection, afterRunId, limit));
    }

    /**
     * Safe without automatic recovery: only CLAIMED is known undelivered. ENTERED remains unknown.
     * Caller MUST write synchronous audit in this same transaction and roll back on audit failure.
     * No owner replacement, Task mutation, run terminal transition or executor submission.
     */
    public SweepResult sweepExpired(String runId) {
        gate();
        lockRun(runId);
        return jdbc.execute((ConnectionCallback<SweepResult>) connection -> {
            var active = claims.findActive(connection, runId);
            if (active.isEmpty()) return new SweepResult(0, 0, false);
            var claim = active.get();
            var members = claims.findExpiredMembers(connection, claim);
            if (members.isEmpty()) return new SweepResult(0, 0, false);
            int cancelled = 0;
            int uncertain = 0;
            for (var member : members) {
                if (member.state() == State.CLAIMED) {
                    var exact = member.member();
                    // Exact CAS, deliberately independent of run terminal status. Any conflict aborts everything.
                    if (nodes.closeResumeAbortedAttempt(exact.nodeRunId(), runId, exact.nodeKey(), exact.attempt(),
                            "resume_aborted: 持久领取已过期且未进入执行", LocalDateTime.now(), 0L) != 1
                            || !claims.closeProvenUndelivered(connection, claim, exact)) {
                        throw new IllegalStateException("expired unentered member conflict");
                    }
                    cancelled++;
                } else if (member.state() == State.ENTERED) {
                    if (!claims.complete(connection, claim, member.member(), State.UNCERTAIN)) {
                        throw new IllegalStateException("expired entered member conflict");
                    }
                    uncertain++;
                }
            }
            boolean closed = claims.finishBatch(connection, claim);
            if (!closed && uncertain == 0 && members.stream().noneMatch(member -> member.state() == State.UNCERTAIN)) {
                throw new IllegalStateException("expired batch close conflict");
            }
            return new SweepResult(cancelled, uncertain, closed);
        });
    }

    private void gate() {
        if (!properties.getEngine().isDurableResumeEnabled()) {
            throw new IllegalStateException("durable resume is disabled");
        }
    }
}
