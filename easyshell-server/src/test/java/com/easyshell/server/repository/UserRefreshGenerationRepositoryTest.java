package com.easyshell.server.repository;

import com.easyshell.server.config.WorkflowSchemaFilterProvider;
import org.hibernate.mapping.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class UserRefreshGenerationRepositoryTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private UserRefreshGenerationRepository repository;

    @BeforeEach
    void setUp() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:refresh_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE sys_user (id BIGINT PRIMARY KEY)");
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V1_2_19__user_refresh_generation.sql")).execute(ds);
        jdbc.update("INSERT INTO sys_user VALUES (1)");
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        repository = new UserRefreshGenerationRepository(jdbc);
    }

    @Test
    void requiresTransactionAndExistingUserAndDoesNotImplicitlyInitialize() {
        assertThrows(IllegalStateException.class, () -> repository.initialize(1));
        assertThrows(IllegalStateException.class, () -> repository.read(1));
        assertThrows(IllegalStateException.class, () -> repository.increment(1));
        tx.executeWithoutResult(status -> {
            assertThrows(IllegalStateException.class, () -> repository.initialize(2));
            assertThrows(IllegalStateException.class, () -> repository.read(1));
            assertThrows(IllegalStateException.class, () -> repository.increment(1));
        });
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_refresh_generation", Integer.class));
    }

    @Test
    void initializesOnceIncrementsAndRollsBackAtomically() {
        tx.executeWithoutResult(status -> {
            assertEquals(0, repository.initialize(1));
            assertEquals(0, repository.read(1));
            assertEquals(1, repository.increment(1));
        });
        assertThrows(RuntimeException.class, () -> tx.executeWithoutResult(status -> {
            assertEquals(2, repository.increment(1));
            throw new RuntimeException("abort");
        }));
        assertEquals(1L, tx.execute(status -> repository.read(1)).longValue());
        assertThrows(RuntimeException.class, () -> tx.executeWithoutResult(status -> repository.initialize(1)));
        assertEquals(1L, tx.execute(status -> repository.read(1)).longValue());
    }

    @Test
    void exhaustionFailsWithoutChangingState() {
        tx.executeWithoutResult(status -> repository.initialize(1));
        jdbc.update("UPDATE user_refresh_generation SET generation = ? WHERE user_id = 1", Long.MAX_VALUE);
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> repository.increment(1)));
        assertEquals(Long.MAX_VALUE, tx.execute(status -> repository.read(1)).longValue());
        assertThrows(RuntimeException.class, () -> jdbc.update(
                "UPDATE user_refresh_generation SET generation = -1 WHERE user_id = 1"));
    }

    @Test
    void concurrentIncrementsSerializeOnUserRow() throws Exception {
        tx.executeWithoutResult(status -> repository.initialize(1));
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> tx.execute(status -> repository.increment(1)));
            var second = pool.submit(() -> tx.execute(status -> repository.increment(1)));
            assertEquals(3L, first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS));
            assertEquals(2L, tx.execute(status -> repository.read(1)).longValue());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void tableIsExcludedFromHibernateDdlAndValidation() {
        var provider = new WorkflowSchemaFilterProvider();
        var table = new Table();
        table.setName("USER_REFRESH_GENERATION");
        assertFalse(provider.getCreateFilter().includeTable(table));
        assertFalse(provider.getDropFilter().includeTable(table));
        assertFalse(provider.getMigrateFilter().includeTable(table));
        assertFalse(provider.getValidateFilter().includeTable(table));
    }
}
