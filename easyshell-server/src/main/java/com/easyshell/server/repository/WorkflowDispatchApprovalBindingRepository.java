package com.easyshell.server.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/** Atomic persistence operations; callers must supply an existing transaction. */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class WorkflowDispatchApprovalBindingRepository {
    private final JdbcTemplate jdbc;

    public WorkflowDispatchApprovalBindingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Evidence(long approvalId, String nodeRunId, Integer attempt, Long userId,
                           String scriptHmac, String intentHmac, String targets, Integer timeoutSec,
                           LocalDateTime consumedAt, String taskId) { }

    /** Read only the dispatch evidence; approval and attempt state are checked under run/node locks. */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<Evidence> find(long approvalId) {
        return jdbc.query("SELECT approval_id, dispatch_node_run_id, dispatch_attempt, dispatch_user_id, " +
                        "dispatch_script_hmac, dispatch_intent_hmac, dispatch_targets, dispatch_timeout_sec, " +
                        "dispatch_consumed_at, dispatch_task_id FROM workflow_dispatch_approval_binding WHERE approval_id = ?",
                (rs, row) -> new Evidence(rs.getLong("approval_id"), rs.getString("dispatch_node_run_id"),
                        rs.getObject("dispatch_attempt", Integer.class), rs.getObject("dispatch_user_id", Long.class),
                        rs.getString("dispatch_script_hmac"), rs.getString("dispatch_intent_hmac"),
                        rs.getString("dispatch_targets"), rs.getObject("dispatch_timeout_sec", Integer.class),
                        rs.getObject("dispatch_consumed_at", LocalDateTime.class), rs.getString("dispatch_task_id")),
                approvalId).stream().findFirst();
    }

    public int create(long approvalId, String runId, String nodeRunId, int attempt, long userId,
                      String scriptHmac, String intentHmac, String targets, int timeoutSec) {
        return jdbc.update("INSERT INTO workflow_dispatch_approval_binding " +
                        "(approval_id, dispatch_node_run_id, dispatch_attempt, dispatch_user_id, " +
                        "dispatch_script_hmac, dispatch_intent_hmac, dispatch_targets, dispatch_timeout_sec) " +
                        "SELECT id, ?, ?, ?, ?, ?, ?, ? FROM workflow_approval " +
                        "WHERE id = ? AND run_id = ? AND node_type = 'script-exec' AND status = 'pending' " +
                        "AND expires_at IS NOT NULL AND expires_at > CURRENT_TIMESTAMP",
                nodeRunId, attempt, userId, scriptHmac, intentHmac, targets, timeoutSec, approvalId, runId);
    }

    public int consume(long approvalId, String runId, String nodeRunId, int attempt, long userId,
                       String scriptHmac, String intentHmac, String targets, int timeoutSec, String taskId) {
        return jdbc.update("UPDATE workflow_dispatch_approval_binding SET dispatch_consumed_at = CURRENT_TIMESTAMP, " +
                        "dispatch_task_id = ? WHERE approval_id = ? AND dispatch_node_run_id = ? " +
                        "AND dispatch_attempt = ? AND dispatch_user_id = ? AND dispatch_script_hmac = ? " +
                        "AND dispatch_intent_hmac = ? AND dispatch_targets = ? AND dispatch_timeout_sec = ? " +
                        "AND dispatch_consumed_at IS NULL AND dispatch_task_id IS NULL " +
                        "AND EXISTS (SELECT 1 FROM workflow_approval a WHERE a.id = workflow_dispatch_approval_binding.approval_id " +
                        "AND a.run_id = ? AND a.node_type = 'script-exec' AND a.status = 'approved' AND a.decided_by IS NOT NULL " +
                        "AND a.decided_by > 0 AND a.decided_at IS NOT NULL " +
                        "AND a.expires_at IS NOT NULL AND a.expires_at > CURRENT_TIMESTAMP)",
                taskId, approvalId, nodeRunId, attempt, userId, scriptHmac, intentHmac, targets, timeoutSec, runId);
    }
}
