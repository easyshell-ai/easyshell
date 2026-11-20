package com.easyshell.server.repository;

import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.entity.Job;
import com.easyshell.server.model.entity.Task;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.time.LocalDateTime;

/** Read-side authorization: unknown task identities are never treated as visible. */
@Repository
public class TaskAccessQueryRepository {
    private final EntityManager em;

    public TaskAccessQueryRepository(EntityManager em) { this.em = em; }

    private static final String VISIBLE = " FROM task t JOIN sys_user u ON u.id = :actor "
            + "AND u.status = 1 AND u.role IN ('viewer', 'operator', 'admin', 'super_admin') "
            + "JOIN task_target_snapshot s ON s.task_id = t.id "
            + "WHERE EXISTS (SELECT 1 FROM host_acl_state state WHERE state.id = 1 AND state.completed = 1) "
            + "AND (t.created_by = u.id OR u.role IN ('admin', 'super_admin')) "
            // Run provenance includes a definition digest and parsed input. Until that proof can
            // be expressed portably in this query, exclude every potentially associated task.
            + "AND (t.source IS NULL OR t.source <> 'workflow') "
            + "AND NOT EXISTS (SELECT 1 FROM workflow_node_run wn WHERE wn.task_id = t.id) "
            + "AND NOT EXISTS (SELECT 1 FROM workflow_run_provenance_task wp WHERE wp.task_id = t.id) "
            + "AND s.completed = 1 AND s.target_count > 0 AND s.target_count = t.total_count "
            + "AND s.target_count = (SELECT COUNT(*) FROM task_target_snapshot_item si WHERE si.task_id = t.id) "
            + "AND NOT EXISTS (SELECT 1 FROM task_target_snapshot_item si WHERE si.task_id = t.id AND "
            + "(si.generation <= 0 OR NOT EXISTS (SELECT 1 FROM host_identity hi JOIN agent a ON a.id = hi.agent_id "
            + "WHERE hi.agent_id = si.agent_id AND hi.generation = si.generation AND hi.active = 1 "
            + "AND EXISTS (SELECT 1 FROM host_acl_grant g WHERE g.agent_id = hi.agent_id "
            + "AND g.generation = hi.generation AND g.permission = 'VIEW' "
            + "AND ((g.subject_type = 'USER' AND g.subject_id = :subject) "
            + "OR (g.subject_type = 'ROLE' AND g.subject_id = u.role)))))) "
            + "AND NOT EXISTS (SELECT 1 FROM job j WHERE j.task_id = t.id AND NOT EXISTS "
            + "(SELECT 1 FROM task_target_snapshot_item si WHERE si.task_id = t.id AND si.agent_id = j.agent_id)) ";

    private void requireMigration() {
        List<?> rows = em.createNativeQuery("SELECT completed FROM host_acl_state WHERE id = 1").getResultList();
        if (rows.isEmpty() || !(rows.get(0) instanceof Number n ? n.intValue() == 1 : Boolean.TRUE.equals(rows.get(0)))) {
            throw new BusinessException(503, "Host ACL migration incomplete");
        }
    }

    private Query bind(Query query, long actor) {
        return query.setParameter("actor", actor).setParameter("subject", Long.toString(actor));
    }

    /** Never trust a possibly stale token for task or workflow administrator access. */
    @Transactional(readOnly = true)
    public String currentRole(long actor) {
        List<?> roles = em.createNativeQuery("SELECT role FROM sys_user WHERE id = :actor AND status = 1 "
                + "AND role IN ('viewer', 'operator', 'admin', 'super_admin')")
                .setParameter("actor", actor).getResultList();
        if (roles.size() != 1) throw new BusinessException(403, "Active user required");
        return roles.get(0).toString();
    }

    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public List<String> provenanceRunIds(String taskId) {
        return em.createNativeQuery("SELECT run_id FROM workflow_run_provenance_task WHERE task_id = :task")
                .setParameter("task", taskId).getResultList();
    }

    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Task> list(long actor) {
        requireMigration();
        return bind(em.createNativeQuery("SELECT t.*" + VISIBLE + "ORDER BY t.created_at DESC, t.id DESC", Task.class), actor)
                .getResultList();
    }

    public record Dashboard(long total, long today, long todaySucceeded, long todayFailed,
                            double successRate, List<Task> recent) { }

    private static final String DASHBOARD_SELECT = "SELECT COUNT(*), "
            + "COALESCE(SUM(CASE WHEN t.created_at >= :day THEN 1 ELSE 0 END),0), "
            + "COALESCE(SUM(CASE WHEN t.created_at >= :day AND t.status = 2 THEN 1 ELSE 0 END),0), "
            + "COALESCE(SUM(CASE WHEN t.created_at >= :day AND t.status = 4 THEN 1 ELSE 0 END),0), "
            + "COALESCE(SUM(CASE WHEN t.status = 2 THEN 1 ELSE 0 END),0)" + VISIBLE;

    /** Exactly the task-list visibility policy, with database-side bounded aggregation. */
    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Dashboard dashboard(long actor, LocalDateTime dayStart) {
        requireMigration();
        Object[] row = (Object[]) bind(em.createNativeQuery(DASHBOARD_SELECT), actor)
                .setParameter("day", dayStart).getSingleResult();
        long total = ((Number) row[0]).longValue();
        List<Task> tasks = bind(em.createNativeQuery("SELECT t.*" + VISIBLE
                + "ORDER BY t.created_at DESC, t.id DESC", Task.class), actor).setMaxResults(10).getResultList();
        // Dashboard only needs a summary; do not serialize scripts or dispatch evidence.
        List<Task> summaries = tasks.stream().map(task -> {
            Task summary = new Task();
            summary.setId(task.getId()); summary.setName(task.getName());
            summary.setStatus(task.getStatus()); summary.setTotalCount(task.getTotalCount());
            summary.setSuccessCount(task.getSuccessCount()); summary.setFailedCount(task.getFailedCount());
            summary.setCreatedAt(task.getCreatedAt());
            return summary;
        }).toList();
        return new Dashboard(total, ((Number) row[1]).longValue(), ((Number) row[2]).longValue(),
                ((Number) row[3]).longValue(), total == 0 ? 0 : ((Number) row[4]).doubleValue() * 100 / total,
                summaries);
    }

    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<Task> page(long actor, Integer status, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size);
        requireMigration();
        String filter = status == null ? "" : "AND t.status = :status ";
        Query count = bind(em.createNativeQuery("SELECT COUNT(*)" + VISIBLE + filter), actor);
        Query data = bind(em.createNativeQuery("SELECT t.*" + VISIBLE + filter
                + "ORDER BY t.created_at DESC, t.id DESC", Task.class), actor);
        if (status != null) { count.setParameter("status", status); data.setParameter("status", status); }
        long total = ((Number) count.getSingleResult()).longValue();
        data.setFirstResult(Math.toIntExact(pageable.getOffset())).setMaxResults(size);
        return new PageImpl<>(data.getResultList(), pageable, total);
    }

    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Job> agentJobs(long actor, String agentId) {
        requireMigration();
        String sql = "SELECT jobs.* FROM job jobs JOIN task t ON t.id = jobs.task_id "
                + "WHERE jobs.agent_id = :agent AND t.id IN (SELECT t.id" + VISIBLE + ") "
                + "ORDER BY jobs.created_at DESC, jobs.id DESC";
        return bind(em.createNativeQuery(sql, Job.class), actor).setParameter("agent", agentId).getResultList();
    }
}
