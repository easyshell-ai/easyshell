package com.easyshell.server.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowRunV3BirthRepositoryTest {
    private static final String DIGEST = "a".repeat(64);
    private JdbcTemplate jdbc;
    private WorkflowRunV3BirthRepository repository;
    private TransactionTemplate transaction;

    @BeforeEach
    void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:birth_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_2_15__workflow_run_v3_birth.sql"))
                .execute(ds);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_2_12__workflow_run_provenance.sql"))
                .execute(ds);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE test_run (id VARCHAR(64) PRIMARY KEY)");
        var manager = new DataSourceTransactionManager(ds);
        transaction = new TransactionTemplate(manager);
        var proxy = new ProxyFactory(new WorkflowRunV3BirthRepository(jdbc));
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        repository = (WorkflowRunV3BirthRepository) proxy.getProxy();
    }

    @Test
    void requiresTransactionAndRollsBackWithRun() {
        var provenance = new WorkflowRunProvenanceRepository(jdbc);
        assertThrows(IllegalTransactionStateException.class, () -> repository.create("r", 1, DIGEST, "NON_HOST_V3"));
        transaction.executeWithoutResult(tx -> {
            jdbc.update("INSERT INTO test_run VALUES (?)", "r");
            provenance.create("r", 1, DIGEST, "NON_HOST_V3");
            repository.create("r", 1, DIGEST, "NON_HOST_V3");
            tx.setRollbackOnly();
        });
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM test_run", Integer.class));
        assertTrue(repository.find("r").isEmpty());
        assertTrue(provenance.findComplete("r").isEmpty());
        transaction.executeWithoutResult(tx -> {
            jdbc.update("INSERT INTO test_run VALUES (?)", "r");
            repository.create("r", 1, DIGEST, "NON_HOST_V3");
        });
        assertEquals(new WorkflowRunV3BirthRepository.Birth("r", 1, DIGEST, "NON_HOST_V3"),
                repository.find("r").orElseThrow());
    }

    @Test
    void duplicateCannotReplaceMarker() {
        transaction.executeWithoutResult(tx -> repository.create("r", 1, DIGEST, "NON_HOST_V3"));
        assertThrows(RuntimeException.class, () -> transaction.executeWithoutResult(tx ->
                repository.create("r", 2, "b".repeat(64), "NON_HOST_V3")));
        assertEquals(1, repository.find("r").orElseThrow().versionId());
    }

    @Test
    void missingInvalidAndDamagedMarkersAreNotEvidence() {
        assertTrue(repository.find("historical").isEmpty());
        assertTrue(repository.find(null).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> transaction.executeWithoutResult(tx ->
                repository.create("r", 0, DIGEST, "NON_HOST_V3")));
        assertThrows(IllegalArgumentException.class, () -> transaction.executeWithoutResult(tx ->
                repository.create("r", 1, DIGEST.toUpperCase(), "NON_HOST_V3")));
        assertThrows(IllegalArgumentException.class, () -> transaction.executeWithoutResult(tx ->
                repository.create("r", 1, DIGEST, "NON_HOST_V2")));
        transaction.executeWithoutResult(tx -> repository.create("r", 1, DIGEST, "NON_HOST_V3"));
        jdbc.update("UPDATE workflow_run_v3_birth SET version_id = 0 WHERE run_id = 'r'");
        assertTrue(repository.find("r").isEmpty());
        assertTrue(repository.exists("r"));
        jdbc.update("UPDATE workflow_run_v3_birth SET version_id = 1, definition_sha256 = ? WHERE run_id = 'r'", "X".repeat(64));
        assertTrue(repository.find("r").isEmpty());
        jdbc.update("UPDATE workflow_run_v3_birth SET definition_sha256 = ?, semantics = 'NON_HOST_V2' WHERE run_id = 'r'", DIGEST);
        assertTrue(repository.find("r").isEmpty());
    }
}
