package com.easyshell.server.repository;

import com.easyshell.server.model.dto.TaskTargetSnapshot;
import com.easyshell.server.model.dto.TaskTargetSnapshot.Target;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TaskTargetSnapshotRepositoryTest {
    private JdbcTemplate jdbc;
    private TaskTargetSnapshotRepository repository;
    private TransactionTemplate transaction;

    @BeforeEach
    void setup() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:target_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_2_11__task_target_snapshot.sql"))
                .execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        repository = new TaskTargetSnapshotRepository(jdbc);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Test
    void completeSnapshotIsImmutableAndIndependentOfCurrentHostIdentity() {
        var targets = new ArrayList<>(List.of(new Target("b", 4), new Target("a", 2)));
        var snapshot = new TaskTargetSnapshot("task", targets);
        targets.clear();
        transaction.executeWithoutResult(status -> repository.create(snapshot));
        assertEquals(List.of(new Target("a", 2), new Target("b", 4)),
                repository.findComplete("task").orElseThrow().targets());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.targets().clear());
        assertThrows(RuntimeException.class, () -> transaction.executeWithoutResult(status ->
                repository.create(new TaskTargetSnapshot("task", List.of(new Target("a", 9))))));
        assertEquals(2, repository.findComplete("task").orElseThrow().targets().get(0).generation());
    }

    @Test
    void legacyIncompleteAndDamagedSnapshotsAreUnknown() {
        assertTrue(repository.findComplete("legacy").isEmpty());
        jdbc.update("INSERT INTO task_target_snapshot VALUES ('partial', 2, 0)");
        jdbc.update("INSERT INTO task_target_snapshot_item VALUES ('partial', 'a', 1)");
        assertTrue(repository.findComplete("partial").isEmpty());
        jdbc.update("UPDATE task_target_snapshot SET completed = 1 WHERE task_id = 'partial'");
        assertTrue(repository.findComplete("partial").isEmpty());
        jdbc.update("INSERT INTO task_target_snapshot_item VALUES ('partial', 'b', 0)");
        assertTrue(repository.findComplete("partial").isEmpty());
    }

    @Test
    void failedTransactionDoesNotPublishAnySnapshot() {
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            repository.create(new TaskTargetSnapshot("rolled-back", List.of(new Target("a", 1))));
            throw new IllegalStateException("task creation failed");
        }));
        assertTrue(repository.findComplete("rolled-back").isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM task_target_snapshot_item", Integer.class));
    }

    @Test
    void invalidOrDuplicateTargetsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Target("a", 0));
        assertThrows(IllegalArgumentException.class, () -> new TaskTargetSnapshot("t", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new TaskTargetSnapshot("t",
                List.of(new Target("a", 1), new Target("a", 2))));
    }
}
