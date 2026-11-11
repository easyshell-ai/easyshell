package com.easyshell.server.repository;

import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.vo.HostPermissionQueryVO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Repository
public class HostPermissionQueryRepository {
    private final JdbcTemplate jdbc;

    public HostPermissionQueryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Return only executable agent IDs, including grants without VIEW, from one guarded snapshot. */
    public List<String> executeTargets(long userId, String cursor, int limit) {
        var rows = jdbc.query("SELECT s.completed, i.agent_id "
                        + "FROM (SELECT 1 AS id) anchor LEFT JOIN host_acl_state s ON s.id = anchor.id "
                        + "LEFT JOIN sys_user u ON u.id = ? AND u.status = 1 "
                        + "AND u.role IN ('viewer', 'operator', 'admin', 'super_admin') "
                        + "LEFT JOIN host_identity i ON i.active = 1 AND s.completed = 1 AND u.id IS NOT NULL "
                        + (cursor == null ? "" : "AND i.agent_id > ? ")
                        + "AND EXISTS (SELECT 1 FROM agent a WHERE a.id = i.agent_id) "
                        + "AND EXISTS (SELECT 1 FROM host_acl_grant g WHERE g.agent_id = i.agent_id "
                        + "AND g.generation = i.generation AND g.permission = 'EXECUTE' "
                        + "AND ((g.subject_type = 'USER' AND g.subject_id = ?) "
                        + "OR (g.subject_type = 'ROLE' AND g.subject_id = u.role))) "
                        + "ORDER BY i.agent_id LIMIT ?",
                (rs, n) -> new ExecuteRow(rs.getBoolean(1), rs.getString(2)),
                cursor == null ? new Object[]{userId, Long.toString(userId), limit}
                        : new Object[]{userId, cursor, Long.toString(userId), limit});
        if (rows.isEmpty() || !rows.get(0).completed())
            throw new BusinessException(503, "Host ACL migration incomplete");
        return rows.stream().map(ExecuteRow::agentId).filter(java.util.Objects::nonNull).toList();
    }

    private record ExecuteRow(boolean completed, String agentId) { }

    /** The sentinel keeps migration state observable even when no host is visible. */
    public List<HostPermissionQueryVO> query(long userId, List<String> agentIds) {
        String ids = agentIds.isEmpty() ? "NULL" : agentIds.stream().map(id -> "?").collect(Collectors.joining(","));
        String grant = "g.agent_id = i.agent_id AND g.generation = i.generation "
                + "AND ((g.subject_type = 'USER' AND g.subject_id = ?) "
                + "OR (g.subject_type = 'ROLE' AND g.subject_id = u.role))";
        String sql = "SELECT s.completed, i.agent_id, i.generation, "
                + "MAX(CASE WHEN g.permission = 'VIEW' THEN 1 ELSE 0 END), "
                + "MAX(CASE WHEN g.permission = 'EXECUTE' THEN 1 ELSE 0 END), "
                + "MAX(CASE WHEN g.permission = 'TERMINAL' AND u.role IN ('operator', 'admin', 'super_admin') THEN 1 ELSE 0 END) "
                + "FROM (SELECT 1 AS id) anchor LEFT JOIN host_acl_state s ON s.id = anchor.id "
                + "LEFT JOIN sys_user u ON u.id = ? AND u.status = 1 "
                + "AND u.role IN ('viewer', 'operator', 'admin', 'super_admin') "
                + "LEFT JOIN host_identity i ON i.active = 1 AND s.completed = 1 AND u.id IS NOT NULL "
                + "AND i.agent_id IN (" + ids + ") "
                + "AND EXISTS (SELECT 1 FROM agent a WHERE a.id = i.agent_id) "
                + "LEFT JOIN host_acl_grant g ON " + grant + " "
                + "GROUP BY s.completed, i.agent_id, i.generation ORDER BY i.agent_id";
        List<Object> args = new ArrayList<>();
        args.add(userId);
        args.addAll(agentIds);
        args.add(Long.toString(userId));
        var rows = jdbc.query(sql, (rs, n) -> new Row(rs.getBoolean(1), rs.getString(2), rs.getLong(3),
                rs.getBoolean(4), rs.getBoolean(5), rs.getBoolean(6)), args.toArray());
        if (rows.isEmpty() || !rows.get(0).completed())
            throw new BusinessException(503, "Host ACL migration incomplete");
        return rows.stream().filter(row -> row.agentId() != null && row.view())
                .map(row -> new HostPermissionQueryVO(row.agentId(), row.generation(), true, row.execute(), row.terminal()))
                .toList();
    }

    private record Row(boolean completed, String agentId, long generation, boolean view, boolean execute, boolean terminal) { }
}
