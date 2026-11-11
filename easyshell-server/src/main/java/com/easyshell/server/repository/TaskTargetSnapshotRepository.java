package com.easyshell.server.repository;

import com.easyshell.server.model.dto.TaskTargetSnapshot;
import com.easyshell.server.model.dto.TaskTargetSnapshot.Target;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public class TaskTargetSnapshotRepository {
    private final JdbcTemplate jdbc;

    public TaskTargetSnapshotRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * Insert once, in the caller's transaction with task creation. Never retry by replacing
     * a snapshot. The caller must roll back the entire transaction on any failure.
     * Target generations must be obtained and protected against identity changes by the caller.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(TaskTargetSnapshot snapshot) {
        jdbc.update("INSERT INTO task_target_snapshot (task_id, target_count, completed) VALUES (?, ?, 0)",
                snapshot.taskId(), snapshot.targets().size());
        for (Target target : snapshot.targets()) {
            jdbc.update("INSERT INTO task_target_snapshot_item (task_id, agent_id, generation) VALUES (?, ?, ?)",
                    snapshot.taskId(), target.agentId(), target.generation());
        }
        if (jdbc.update("UPDATE task_target_snapshot SET completed = 1 WHERE task_id = ? AND completed = 0",
                snapshot.taskId()) != 1) throw new IllegalStateException("Snapshot completion failed");
    }

    /** One statement observes the header and all items together. Empty means unknown, not no targets. */
    public Optional<TaskTargetSnapshot> findComplete(String taskId) {
        List<Row> rows = jdbc.query("SELECT s.target_count, s.completed, i.agent_id, i.generation "
                        + "FROM task_target_snapshot s LEFT JOIN task_target_snapshot_item i ON i.task_id = s.task_id "
                        + "WHERE s.task_id = ? ORDER BY i.agent_id",
                (rs, n) -> new Row(rs.getInt(1), rs.getBoolean(2), rs.getString(3), rs.getLong(4)), taskId);
        if (rows.isEmpty() || !rows.get(0).completed() || rows.get(0).count() <= 0
                || rows.size() != rows.get(0).count()
                || rows.stream().anyMatch(row -> row.agentId() == null || row.generation() <= 0)) {
            return Optional.empty();
        }
        return Optional.of(new TaskTargetSnapshot(taskId,
                rows.stream().map(row -> new Target(row.agentId(), row.generation())).toList()));
    }

    private record Row(int count, boolean completed, String agentId, long generation) { }
}
