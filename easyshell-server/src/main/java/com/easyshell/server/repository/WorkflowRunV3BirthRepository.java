package com.easyshell.server.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** Independent birth evidence, not an authorization or a provenance decision. */
@Repository
public class WorkflowRunV3BirthRepository {
    public static final String SEMANTICS = "NON_HOST_V3";

    private final JdbcTemplate jdbc;

    public WorkflowRunV3BirthRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Birth(String runId, long versionId, String digest, String semantics) { }

    /** The caller must create the run and this marker in the same transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(String runId, long versionId, String digest, String semantics) {
        if (!valid(runId, versionId, digest, semantics)) throw new IllegalArgumentException("Invalid V3 birth marker");
        jdbc.update("INSERT INTO workflow_run_v3_birth (run_id, version_id, definition_sha256, semantics) VALUES (?, ?, ?, ?)",
                runId, versionId, digest, semantics);
    }

    /** Missing, malformed, or unrecognized markers are never positive evidence. */
    public Optional<Birth> find(String runId) {
        if (runId == null || runId.isBlank() || runId.length() > 64) return Optional.empty();
        List<Birth> rows = jdbc.query("SELECT run_id, version_id, definition_sha256, semantics FROM workflow_run_v3_birth WHERE run_id = ?",
                (rs, n) -> new Birth(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4)), runId);
        if (rows.size() != 1) return Optional.empty();
        Birth birth = rows.get(0);
        return runId.equals(birth.runId()) && valid(birth.runId(), birth.versionId(), birth.digest(), birth.semantics())
                ? Optional.of(birth) : Optional.empty();
    }

    /** Distinguish absent evidence from a present but damaged marker. */
    public boolean exists(String runId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM workflow_run_v3_birth WHERE run_id = ?",
                Integer.class, runId);
        return count == null || count != 0;
    }

    private static boolean valid(String runId, long versionId, String digest, String semantics) {
        return runId != null && !runId.isBlank() && runId.length() <= 64 && versionId > 0
                && digest != null && digest.matches("[0-9a-f]{64}") && SEMANTICS.equals(semantics);
    }
}
