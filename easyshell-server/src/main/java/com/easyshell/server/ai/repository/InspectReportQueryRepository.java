package com.easyshell.server.ai.repository;

import com.easyshell.server.ai.model.entity.AiInspectReport;
import com.easyshell.server.common.exception.BusinessException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class InspectReportQueryRepository {
    // The same predicate is used for count and data; unknown historical provenance is excluded.
    static final String VISIBLE = " FROM ai_inspect_report r "
            + "JOIN ai_inspect_report_task_binding b ON b.report_id = r.id "
            + "JOIN task t ON t.id = b.task_id AND t.created_by = r.created_by "
            + "JOIN task_target_snapshot s ON s.task_id = t.id "
            + "JOIN sys_user u ON u.id = :actor AND u.status = 1 AND u.role IN ('viewer','operator','admin','super_admin') "
            + "WHERE (r.created_by = u.id OR u.role IN ('admin','super_admin')) "
            + "AND t.source = 'user' "
            + "AND NOT EXISTS (SELECT 1 FROM workflow_node_run wn WHERE wn.task_id = t.id) "
            + "AND NOT EXISTS (SELECT 1 FROM workflow_run_provenance_task wp WHERE wp.task_id = t.id) "
            + "AND NOT EXISTS (SELECT 1 FROM workflow_dispatch_approval_binding wb WHERE wb.dispatch_task_id = t.id) "
            + "AND EXISTS (SELECT 1 FROM host_acl_state state WHERE state.id = 1 AND state.completed = 1) "
            + "AND s.completed = 1 AND s.target_count > 0 AND s.target_count = t.total_count "
            + "AND s.target_count = (SELECT COUNT(*) FROM task_target_snapshot_item si WHERE si.task_id = t.id) "
            + "AND s.target_count = (SELECT COUNT(*) FROM job j WHERE j.task_id = t.id) "
            + "AND NOT EXISTS (SELECT 1 FROM task_target_snapshot_item si WHERE si.task_id = t.id AND "
            + "(si.generation <= 0 OR NOT EXISTS (SELECT 1 FROM job j WHERE j.task_id = t.id AND j.agent_id = si.agent_id) "
            + "OR NOT EXISTS (SELECT 1 FROM host_identity hi JOIN agent a ON a.id = hi.agent_id "
            + "WHERE hi.agent_id = si.agent_id AND hi.generation = si.generation AND hi.active = 1 "
            + "AND EXISTS (SELECT 1 FROM host_acl_grant g WHERE g.agent_id = hi.agent_id "
            + "AND g.generation = hi.generation AND g.permission = 'VIEW' "
            + "AND ((g.subject_type = 'USER' AND g.subject_id = :subject) "
            + "OR (g.subject_type = 'ROLE' AND g.subject_id = u.role)))))) "
            + "AND NOT EXISTS (SELECT 1 FROM job j WHERE j.task_id = t.id AND NOT EXISTS "
            + "(SELECT 1 FROM task_target_snapshot_item si WHERE si.task_id = t.id AND si.agent_id = j.agent_id)) ";

    private final EntityManager em;

    private Query bind(Query query, long actor) {
        return query.setParameter("actor", actor).setParameter("subject", Long.toString(actor));
    }

    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<AiInspectReport> page(long actor, int page, int size) {
        List<?> state = em.createNativeQuery("SELECT completed FROM host_acl_state WHERE id = 1").getResultList();
        if (state.size() != 1 || !(state.get(0) instanceof Number n ? n.intValue() == 1 : Boolean.TRUE.equals(state.get(0))))
            throw new BusinessException(503, "Host ACL migration incomplete");
        var pageable = PageRequest.of(page, size);
        long count = ((Number) bind(em.createNativeQuery("SELECT COUNT(*)" + VISIBLE), actor).getSingleResult()).longValue();
        Query data = bind(em.createNativeQuery("SELECT r.*" + VISIBLE + "ORDER BY r.created_at DESC, r.id DESC", AiInspectReport.class), actor);
        data.setFirstResult(Math.toIntExact(pageable.getOffset())).setMaxResults(size);
        return new PageImpl<>(data.getResultList(), pageable, count);
    }
}
