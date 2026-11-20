package com.easyshell.server.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public class WorkflowRunProvenanceRepository {
    private final JdbcTemplate jdbc;

    public WorkflowRunProvenanceRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Provenance(long versionId, String digest, String kind, List<String> taskIds) { }
    private record Row(long versionId, String digest, String kind, int count, int completed, String taskId) { }

    /** Must commit or roll back with creation of the run. Never backfill historical runs. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(String runId, long versionId, String digest, String kind) {
        jdbc.update("INSERT INTO workflow_run_provenance VALUES (?, ?, ?, ?, 0, 1)",
                runId, versionId, digest, kind);
    }

    /** Must commit or roll back with task creation and its target snapshot. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void associateTask(String runId, String taskId) {
        if (runId == null || runId.isBlank() || taskId == null || taskId.isBlank() || taskId.length() > 64)
            throw new IllegalArgumentException("Invalid provenance association");
        int updated = jdbc.update("UPDATE workflow_run_provenance SET task_count = task_count + 1 "
                + "WHERE run_id = ? AND source_kind = 'HOST' AND completed = 1 AND task_count >= 0", runId);
        if (updated != 1) throw new IllegalStateException("Unknown or invalid run provenance");
        jdbc.update("INSERT INTO workflow_run_provenance_task (run_id, task_id) VALUES (?, ?)", runId, taskId);
    }

    /** A single statement sees the count and the associations at the same database snapshot. */
    public Optional<Provenance> findComplete(String runId) {
        List<Row> rows = jdbc.query("SELECT p.version_id, p.definition_sha256, p.source_kind, p.task_count, "
                        + "p.completed, t.task_id FROM workflow_run_provenance p LEFT JOIN "
                        + "workflow_run_provenance_task t ON t.run_id = p.run_id WHERE p.run_id = ? ORDER BY t.task_id",
                (rs, n) -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                        rs.getInt(5), rs.getString(6)), runId);
        if (rows.isEmpty()) return Optional.empty();
        Row h = rows.get(0);
        List<String> ids = rows.stream().map(Row::taskId).filter(java.util.Objects::nonNull).toList();
        if (h.versionId() <= 0 || h.digest() == null || !h.digest().matches("[0-9a-f]{64}")
                || h.completed() != 1 || h.count() < 0 || ids.size() != h.count()
                || ids.stream().anyMatch(String::isBlank)
                || !("HOST".equals(h.kind()) || "PURE".equals(h.kind()) || "NON_HOST_V1".equals(h.kind())
                    || "NON_HOST_V2".equals(h.kind()) || "NON_HOST_V3".equals(h.kind()) || "NON_HOST_CODE".equals(h.kind()))
                || (("PURE".equals(h.kind()) || "NON_HOST_V1".equals(h.kind())
                    || "NON_HOST_V2".equals(h.kind()) || "NON_HOST_V3".equals(h.kind()) || "NON_HOST_CODE".equals(h.kind())) && !ids.isEmpty())) return Optional.empty();
        return Optional.of(new Provenance(h.versionId(), h.digest(), h.kind(), ids));
    }
}
