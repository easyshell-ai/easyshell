package com.easyshell.server.service;

import com.easyshell.server.repository.AgentEnrollmentTokenRepository;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

class AgentEnrollmentTokenDatabaseTest {
    @Test
    void revocationIsAgentWideAndRollsBackWithCaller() {
        String url = "jdbc:h2:mem:enrollment_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").load().migrate();
        var ds = new DriverManagerDataSource(url, "sa", "");
        var jdbc = new JdbcTemplate(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var service = new AgentEnrollmentTokenService(new AgentEnrollmentTokenRepository(jdbc));
        Instant expiry = Instant.now().plusSeconds(120);
        String first = tx.execute(s -> service.issue("host", 1, 42, expiry));
        String other = tx.execute(s -> service.issue("other", 1, 42, expiry));
        tx.executeWithoutResult(s -> {
            service.issue("host", 2, 42, expiry);
            s.setRollbackOnly();
        });
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM agent_enrollment_token "
                + "WHERE agent_id = 'host' AND consumed_at IS NULL", Integer.class));
        String second = tx.execute(s -> service.issue("host", 2, 42, expiry));
        assertEquals(Boolean.FALSE, tx.execute(s -> service.consume(first, "host", 1)));

        // Model legacy rows that were simultaneously outstanding before this invariant existed.
        jdbc.update("UPDATE agent_enrollment_token SET consumed_at = NULL WHERE agent_id = 'host'");
        assertEquals(Boolean.FALSE, tx.execute(s -> service.consume(second, "host", 1)));
        tx.executeWithoutResult(s -> {
            assertTrue(service.consume(second, "host", 2));
            s.setRollbackOnly();
        });
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM agent_enrollment_token "
                + "WHERE agent_id = 'host' AND consumed_at IS NULL", Integer.class));
        assertEquals(Boolean.TRUE, tx.execute(s -> service.consume(second, "host", 2)));
        assertEquals(Boolean.FALSE, tx.execute(s -> service.consume(first, "host", 1)));
        assertEquals(Boolean.TRUE, tx.execute(s -> service.consume(other, "other", 1)));
    }

    @Test
    void migrationAndAtomicConsumption() throws Exception {
        String url = "jdbc:h2:mem:enrollment_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").load().migrate();
        var ds = new DriverManagerDataSource(url, "sa", "");
        var jdbc = new JdbcTemplate(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var service = new AgentEnrollmentTokenService(new AgentEnrollmentTokenRepository(jdbc));
        String token = tx.execute(s -> service.issue("host", 3, 42, Instant.now().plusSeconds(120)));
        assertNotEquals(token, jdbc.queryForObject("SELECT token_hash FROM agent_enrollment_token", String.class));
        assertEquals(Boolean.FALSE, tx.execute(s -> service.consume(token, "other", 3)));
        assertEquals(Boolean.FALSE, tx.execute(s -> service.consume(token, "host", 4)));
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> { start.await(); return tx.execute(s -> service.consume(token, "host", 3)); });
            var b = pool.submit(() -> { start.await(); return tx.execute(s -> service.consume(token, "host", 3)); });
            start.countDown();
            assertNotEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(Boolean.FALSE, tx.execute(s -> service.consume(token, "host", 3)));
        String expired = tx.execute(s -> service.issue("host", 3, 42, Instant.now().plusSeconds(120)));
        jdbc.update("UPDATE agent_enrollment_token SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1' SECOND "
                + "WHERE consumed_at IS NULL");
        assertEquals(Boolean.FALSE, tx.execute(s -> service.consume(expired, "host", 3)));
        String rollback = tx.execute(s -> service.issue("host", 3, 42, Instant.now().plusSeconds(120)));
        tx.executeWithoutResult(s -> { assertTrue(service.consume(rollback, "host", 3)); s.setRollbackOnly(); });
        assertEquals(Boolean.TRUE, tx.execute(s -> service.consume(rollback, "host", 3)));
    }
}
