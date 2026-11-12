package com.easyshell.server.service;

import com.easyshell.server.repository.AgentRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class HostIdentityDatabaseTest {
    @Test
    void emptyH2MigrationAndSerializedRecreation() throws Exception {
        String url = "jdbc:h2:mem:identity_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").load().migrate();
        exercise(url, "sa", "");
    }

    // Use only a dedicated, already migrated test database. Never creates or clears its schema.
    @Test
    @EnabledIfEnvironmentVariable(named = "HOST_IDENTITY_MYSQL_URL", matches = ".+")
    void mysqlSerializedRecreation() throws Exception {
        exercise(System.getenv("HOST_IDENTITY_MYSQL_URL"), System.getenv("HOST_IDENTITY_MYSQL_USER"),
                System.getenv("HOST_IDENTITY_MYSQL_PASSWORD"));
    }

    private void exercise(String url, String user, String password) throws Exception {
        var ds = new DriverManagerDataSource(url, user, password);
        var jdbc = new JdbcTemplate(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var service = new HostIdentityService(jdbc, mock(AgentRepository.class));
        String id = "test-" + UUID.randomUUID();
        tx.executeWithoutResult(s -> assertEquals(1, service.activate(service.lock(id)).generation()));
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var deletion = pool.submit(() -> tx.executeWithoutResult(s -> {
                var identity = service.lock(id);
                service.retire(identity);
                locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));
            assertTrue(locked.await(10, TimeUnit.SECONDS));
            var registration = pool.submit(() -> tx.execute(s -> service.activate(service.lock(id))));
            release.countDown();
            deletion.get(15, TimeUnit.SECONDS);
            assertEquals(2, registration.get(15, TimeUnit.SECONDS).generation());
            assertEquals(2L, tx.execute(s -> service.activate(service.lock(id)).generation()).longValue());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
