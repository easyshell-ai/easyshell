package com.easyshell.server.repository;

import com.easyshell.server.common.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

@Repository
public class HostAclRepository {
    private final JdbcTemplate jdbc;

    public HostAclRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean lockCompleted() {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT completed FROM host_acl_state WHERE id = 1 FOR UPDATE", Boolean.class));
    }

    public void complete() {
        if (jdbc.update("UPDATE host_acl_state SET completed = 1 WHERE id = 1 AND completed = 0") != 1)
            throw new IllegalStateException("Host ACL migration state changed unexpectedly");
    }

    public boolean completed() {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT completed FROM host_acl_state WHERE id = 1", Boolean.class));
    }

    /** One statement binds the grant to the active identity and current user snapshot. */
    public OptionalLong authorizedGeneration(long userId, String agentId, String permission) {
        var generations = jdbc.query("SELECT i.generation FROM host_identity i "
                        + "JOIN agent a ON a.id = i.agent_id "
                        + "JOIN sys_user u ON u.id = ? "
                        + "WHERE i.agent_id = ? AND i.active = 1 AND u.status = 1 "
                        + "AND u.role IN ('viewer', 'operator', 'admin', 'super_admin') "
                        + "AND (? <> 'TERMINAL' OR u.role IN ('operator', 'admin', 'super_admin')) "
                        + "AND EXISTS (SELECT 1 FROM host_acl_state s WHERE s.id = 1 AND s.completed = 1) "
                        + "AND EXISTS (SELECT 1 FROM host_acl_grant g WHERE g.agent_id = i.agent_id "
                        + "AND g.generation = i.generation AND g.permission = ? "
                        + "AND ((g.subject_type = 'USER' AND g.subject_id = ?) "
                        + "OR (g.subject_type = 'ROLE' AND g.subject_id = u.role)))",
                (rs, n) -> rs.getLong(1), userId, agentId, permission, permission, Long.toString(userId));
        return generations.isEmpty() ? OptionalLong.empty() : OptionalLong.of(generations.get(0));
    }

    public Optional<Long> activeGeneration(String agentId) {
        return jdbc.query("SELECT generation FROM host_identity WHERE agent_id = ? AND active = 1",
                (rs, n) -> rs.getLong(1), agentId).stream().findFirst();
    }

    public boolean hasGrant(String agentId, long generation, long userId, String role, String permission) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM host_acl_grant WHERE agent_id = ? AND generation = ? "
                        + "AND permission = ? AND ((subject_type = 'USER' AND subject_id = ?) "
                        + "OR (subject_type = 'ROLE' AND subject_id = ?))", Integer.class,
                agentId, generation, permission, Long.toString(userId), role);
        return count != null && count > 0;
    }

    /** Call only while holding the corresponding host identity lock. */
    public boolean hasExactGrant(String agentId, long generation, String type, String subject, String permission) {
        return !jdbc.query("SELECT permission FROM host_acl_grant WHERE agent_id = ? AND generation = ? "
                        + "AND subject_type = ? AND subject_id = ? AND permission = ? FOR UPDATE",
                (rs, row) -> rs.getString(1), agentId, generation, type, subject, permission).isEmpty();
    }

    /** The caller holds the identity lock and commits this with the grant and audit. */
    public void advanceVersion(String agentId) {
        if (jdbc.update("UPDATE host_identity SET acl_version = acl_version + 1 "
                + "WHERE agent_id = ? AND acl_version < 9223372036854775807", agentId) != 1)
            throw new IllegalStateException("Host ACL version unavailable or exhausted");
    }

    /** Call with the identity row locked; older server instances must not run concurrently. */
    public long version(String agentId) {
        Long value = jdbc.queryForObject("SELECT acl_version FROM host_identity WHERE agent_id = ? FOR UPDATE",
                Long.class, agentId);
        if (value == null || value < 0) throw new IllegalStateException("Host ACL version unavailable");
        return value;
    }

    public List<String> subjectPermissions(String agentId, long generation, String type, String subject) {
        return jdbc.query("SELECT permission FROM host_acl_grant WHERE agent_id = ? AND generation = ? "
                        + "AND subject_type = ? AND subject_id = ? ORDER BY permission FOR UPDATE",
                (rs, n) -> rs.getString(1), agentId, generation, type, subject);
    }

    public void grant(String agentId, long generation, String type, String subject, String permission) {
        jdbc.update("INSERT INTO host_acl_grant (agent_id, generation, subject_type, subject_id, permission) "
                + "VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE permission = permission",
                agentId, generation, type, subject, permission);
    }

    public void revoke(String agentId, long generation, String type, String subject, String permission) {
        jdbc.update("DELETE FROM host_acl_grant WHERE agent_id = ? AND generation = ? AND subject_type = ? "
                + "AND subject_id = ? AND permission = ?", agentId, generation, type, subject, permission);
    }

    public List<Grant> list(String agentId, long generation) {
        return jdbc.query("SELECT subject_type, subject_id, permission FROM host_acl_grant "
                        + "WHERE agent_id = ? AND generation = ? ORDER BY subject_type, subject_id, permission",
                (rs, n) -> new Grant(rs.getString(1), rs.getString(2), rs.getString(3)), agentId, generation);
    }

    /** Checks migration and administrator state in the same statement as the read. */
    public List<CatalogHost> catalog(long actor, int limit, long offset) {
        var rows = jdbc.query("SELECT s.completed, u.id, i.agent_id, a.hostname, i.generation "
                        + "FROM (SELECT 1 AS id) anchor "
                        + "LEFT JOIN host_acl_state s ON s.id = anchor.id "
                        + "LEFT JOIN sys_user u ON u.id = ? AND u.status = 1 AND u.role IN ('admin', 'super_admin') "
                        + "LEFT JOIN host_identity i ON i.active = 1 AND s.completed = 1 AND u.id IS NOT NULL "
                        + "AND EXISTS (SELECT 1 FROM agent existing WHERE existing.id = i.agent_id) "
                        + "LEFT JOIN agent a ON a.id = i.agent_id "
                        + "ORDER BY i.agent_id LIMIT ? OFFSET ?",
                (rs, n) -> new CatalogRow(rs.getBoolean(1), rs.getObject(2) != null,
                        rs.getString(3), rs.getString(4), rs.getLong(5)), actor, limit, offset);
        // An offset past the end removes the sentinel as well. Check authorization separately
        // in that case, while still using the guarded statement for all returned host data.
        if (rows.isEmpty() && offset > 0) return catalogEmptyPage(actor);
        if (rows.isEmpty()) throw new BusinessException(503, "Host ACL migration incomplete");
        CatalogRow first = rows.get(0);
        if (!first.completed()) throw new BusinessException(503, "Host ACL migration incomplete");
        if (!first.admin()) throw new BusinessException(403, "Administrator required");
        return rows.stream().filter(row -> row.agentId() != null)
                .map(row -> new CatalogHost(row.agentId(), row.hostname(), row.generation())).toList();
    }

    private List<CatalogHost> catalogEmptyPage(long actor) {
        // Reuse the guarded query at offset zero to distinguish an empty page from denial.
        catalog(actor, 1, 0);
        return List.of();
    }

    private record CatalogRow(boolean completed, boolean admin, String agentId, String hostname, long generation) { }
    public record CatalogHost(String agentId, String hostname, long generation) { }

    public record Grant(String subjectType, String subjectId, String permission) { }
}
