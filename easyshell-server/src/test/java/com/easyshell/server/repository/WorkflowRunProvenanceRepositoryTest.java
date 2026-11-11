package com.easyshell.server.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowRunProvenanceRepositoryTest {
    private JdbcTemplate jdbc;
    private WorkflowRunProvenanceRepository repository;
    private TransactionTemplate transaction;
    private static final String DIGEST = "a".repeat(64);

    @BeforeEach
    void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:prov_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_2_12__workflow_run_provenance.sql"))
                .execute(ds);
        jdbc = new JdbcTemplate(ds);
        repository = new WorkflowRunProvenanceRepository(jdbc);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    @Test
    void missingAndDamagedRecordsAreUnknown() {
        assertTrue(repository.findComplete("legacy").isEmpty());
        transaction.executeWithoutResult(s -> repository.create("r", 1, DIGEST, "HOST"));
        jdbc.update("UPDATE workflow_run_provenance SET task_count = 1 WHERE run_id = 'r'");
        assertTrue(repository.findComplete("r").isEmpty());
        jdbc.update("UPDATE workflow_run_provenance SET task_count = 0, completed = 0 WHERE run_id = 'r'");
        assertTrue(repository.findComplete("r").isEmpty());
    }

    @Test
    void associationsAreAtomicAndUnique() {
        transaction.executeWithoutResult(s -> repository.create("r", 1, DIGEST, "HOST"));
        transaction.executeWithoutResult(s -> repository.associateTask("r", "t"));
        assertEquals(java.util.List.of("t"), repository.findComplete("r").orElseThrow().taskIds());
        assertThrows(RuntimeException.class, () -> transaction.executeWithoutResult(s -> repository.associateTask("r", "t")));
        assertEquals(java.util.List.of("t"), repository.findComplete("r").orElseThrow().taskIds());
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(s -> {
            repository.associateTask("r", "other");
            throw new IllegalStateException();
        }));
        assertEquals(java.util.List.of("t"), repository.findComplete("r").orElseThrow().taskIds());
    }

    @Test
    void pureRunsCannotAssociateTasks() {
        transaction.executeWithoutResult(s -> repository.create("r", 1, DIGEST, "PURE"));
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(s -> repository.associateTask("r", "t")));
    }

    @Test
    void nonHostVersionIsExplicitAndCannotAssociateTasks() {
        transaction.executeWithoutResult(s -> repository.create("r", 1, DIGEST, "NON_HOST_V1"));
        assertEquals("NON_HOST_V1", repository.findComplete("r").orElseThrow().kind());
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(s -> repository.associateTask("r", "t")));
        jdbc.update("UPDATE workflow_run_provenance SET source_kind = 'NON_HOST_V2' WHERE run_id = 'r'");
        assertEquals("NON_HOST_V2", repository.findComplete("r").orElseThrow().kind());
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(s -> repository.associateTask("r", "t")));
        jdbc.update("UPDATE workflow_run_provenance SET source_kind = 'NON_HOST_V3' WHERE run_id = 'r'");
        assertEquals("NON_HOST_V3", repository.findComplete("r").orElseThrow().kind());
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(s -> repository.associateTask("r", "t")));
    }

    @Test
    void persistedV3RejectsDamagedAssociationsAfterRepositoryRecreation() {
        transaction.executeWithoutResult(s -> repository.create("r", 1, DIGEST, "NON_HOST_V3"));
        var reopened = new WorkflowRunProvenanceRepository(jdbc);
        assertEquals("NON_HOST_V3", reopened.findComplete("r").orElseThrow().kind());
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(s ->
                reopened.associateTask("r", "t")));
        jdbc.update("INSERT INTO workflow_run_provenance_task VALUES ('r', 't')");
        jdbc.update("UPDATE workflow_run_provenance SET task_count = 1 WHERE run_id = 'r'");
        assertTrue(new WorkflowRunProvenanceRepository(jdbc).findComplete("r").isEmpty());
    }
}
