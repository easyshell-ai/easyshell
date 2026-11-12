package com.easyshell.server.service;

import com.easyshell.server.repository.AgentEnrollmentTokenRepository;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.UUID;
import java.util.Base64;
import java.util.ArrayList;
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

class AgentBoundKeyDatabaseTest {
    @Test
    void challengeLimitSerializesIssuanceAndIgnoresExpiredAndConsumedRows() throws Exception {
        String url = "jdbc:h2:mem:challenge_limit_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";
        Flyway.configure().dataSource(url, "sa", "").load().migrate();
        var ds = new DriverManagerDataSource(url, "sa", "");
        var jdbc = new JdbcTemplate(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var service = new AgentBoundKeyService(jdbc, new AgentEnrollmentTokenService(new AgentEnrollmentTokenRepository(jdbc)));
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        jdbc.update("INSERT INTO host_identity (agent_id, generation, active) VALUES ('host', 1, 1), ('other', 1, 1)");
        String key = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        jdbc.update("INSERT INTO agent_bound_key (agent_id, generation, public_key) VALUES ('host', 1, ?), ('other', 1, ?)", key, key);
        int limit = AgentBoundKeyService.MAX_OUTSTANDING_CHALLENGES;
        for (int i = 0; i < limit - 1; i++) {
            tx.execute(s -> service.challenge("host", 1, Instant.now().plusSeconds(120)));
        }
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(8);
        try {
            var results = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 8; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        tx.execute(s -> service.challenge("host", 1, Instant.now().plusSeconds(120)));
                        return true;
                    } catch (IllegalStateException e) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int successes = 0;
            for (var result : results) if (result.get(15, TimeUnit.SECONDS)) successes++;
            assertEquals(1, successes);
        } finally { pool.shutdownNow(); }
        assertEquals(limit, jdbc.queryForObject("SELECT COUNT(*) FROM agent_key_challenge WHERE agent_id = 'host'", Integer.class));
        assertNotNull(tx.execute(s -> service.challenge("other", 1, Instant.now().plusSeconds(120))));
        assertThrows(IllegalStateException.class, () -> tx.execute(s -> service.challenge("host", 1, Instant.now().plusSeconds(120))));

        jdbc.update("UPDATE agent_key_challenge SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1' SECOND "
                + "WHERE challenge_hash = (SELECT MIN(challenge_hash) FROM agent_key_challenge WHERE agent_id = 'host')");
        String fresh = tx.execute(s -> service.challenge("host", 1, Instant.now().plusSeconds(120)));
        assertEquals(Boolean.TRUE, tx.execute(s -> service.verify("host", 1, fresh, sign(pair, "host", 1, fresh))));
        assertNotNull(tx.execute(s -> service.challenge("host", 1, Instant.now().plusSeconds(120))));
        assertEquals(limit + 2, jdbc.queryForObject("SELECT COUNT(*) FROM agent_key_challenge WHERE agent_id = 'host'", Integer.class));
        Instant dbNow = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", java.sql.Timestamp.class).toInstant();
        assertThrows(IllegalArgumentException.class, () -> tx.execute(s -> service.challenge("other", 1, dbNow.minusSeconds(1))));
        assertThrows(IllegalArgumentException.class, () -> tx.execute(s -> service.challenge("other", 1, dbNow.plusSeconds(310))));
    }

    @Test
    void bindingAndChallengeAreIsolatedAtomicAndGenerationBound() throws Exception {
        String url = "jdbc:h2:mem:bound_key_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").load().migrate();
        var ds = new DriverManagerDataSource(url, "sa", "");
        var jdbc = new JdbcTemplate(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var tokens = new AgentEnrollmentTokenService(new AgentEnrollmentTokenRepository(jdbc));
        var service = new AgentBoundKeyService(jdbc, tokens);
        jdbc.update("INSERT INTO host_identity (agent_id, generation, active) VALUES ('host', 1, 1), ('legacy', 7, 1)");
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair wrong = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String token = tx.execute(s -> tokens.issue("host", 1, 42, Instant.now().plusSeconds(120)));
        byte[] proof = signBinding(pair, "host", 1, token, pair.getPublic().getEncoded());
        byte[] altered = proof.clone();
        altered[0] ^= 1;
        String otherToken = tx.execute(s -> tokens.issue("legacy", 7, 42, Instant.now().plusSeconds(120)));
        assertEquals(Boolean.FALSE, tx.execute(s -> service.bind("host", 1, token, new byte[44], proof)));
        assertEquals(Boolean.FALSE, tx.execute(s -> service.bind("host", 1, token, pair.getPublic().getEncoded(), null)));
        assertEquals(Boolean.FALSE, tx.execute(s -> service.bind("host", 1, token, pair.getPublic().getEncoded(), altered)));
        assertEquals(Boolean.FALSE, tx.execute(s -> service.bind("host", 1, token, wrong.getPublic().getEncoded(), proof)));
        assertEquals(Boolean.FALSE, tx.execute(s -> service.bind("host", 1, token, pair.getPublic().getEncoded(),
                signBinding(wrong, "host", 1, token, pair.getPublic().getEncoded()))));
        assertEquals(Boolean.FALSE, tx.execute(s -> service.bind("host", 1, token, pair.getPublic().getEncoded(),
                signBinding(pair, "legacy", 1, token, pair.getPublic().getEncoded()))));
        assertEquals(Boolean.FALSE, tx.execute(s -> service.bind("host", 1, token, pair.getPublic().getEncoded(),
                signBinding(pair, "host", 2, token, pair.getPublic().getEncoded()))));
        assertEquals(Boolean.FALSE, tx.execute(s -> service.bind("host", 1, token, pair.getPublic().getEncoded(),
                signBinding(pair, "host", 1, otherToken, pair.getPublic().getEncoded()))));
        assertEquals(Boolean.FALSE, tx.execute(s -> service.bind("host", 1, token, pair.getPublic().getEncoded(),
                sign(pair, "host", 1, token))));
        tx.executeWithoutResult(s -> {
            assertTrue(service.bind("host", 1, token, pair.getPublic().getEncoded(), proof));
            s.setRollbackOnly();
        });
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM agent_bound_key", Integer.class));
        assertEquals(Boolean.TRUE, tx.execute(s -> service.bind("host", 1, token, pair.getPublic().getEncoded(), proof)));
        assertEquals(Boolean.FALSE, tx.execute(s -> service.bind("host", 1, token, pair.getPublic().getEncoded(), proof)));
        assertEquals(7L, jdbc.queryForObject("SELECT generation FROM host_identity WHERE agent_id = 'legacy'", Long.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM agent_bound_key WHERE agent_id = 'legacy'", Integer.class));

        String challenge = tx.execute(s -> service.challenge("host", 1, Instant.now().plusSeconds(120)));
        assertNotEquals(challenge, jdbc.queryForObject("SELECT challenge_hash FROM agent_key_challenge", String.class));
        byte[] signed = sign(pair, "host", 1, challenge);
        assertEquals(Boolean.FALSE, tx.execute(s -> service.verify("host", 1, challenge, sign(wrong, "host", 1, challenge))));
        assertEquals(Boolean.FALSE, tx.execute(s -> service.verify("host", 2, challenge, signed)));
        tx.executeWithoutResult(s -> { assertTrue(service.verify("host", 1, challenge, signed)); s.setRollbackOnly(); });
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> { start.await(); return tx.execute(s -> service.verify("host", 1, challenge, signed)); });
            var b = pool.submit(() -> { start.await(); return tx.execute(s -> service.verify("host", 1, challenge, signed)); });
            start.countDown();
            assertNotEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
        assertEquals(Boolean.FALSE, tx.execute(s -> service.verify("host", 1, challenge, signed)));

        String expired = tx.execute(s -> service.challenge("host", 1, Instant.now().plusSeconds(120)));
        jdbc.update("UPDATE agent_key_challenge SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1' SECOND WHERE consumed_at IS NULL");
        assertEquals(Boolean.FALSE, tx.execute(s -> service.verify("host", 1, expired, sign(pair, "host", 1, expired))));
        String retired = tx.execute(s -> service.challenge("host", 1, Instant.now().plusSeconds(120)));
        jdbc.update("UPDATE host_identity SET generation = 2 WHERE agent_id = 'host'");
        assertEquals(Boolean.FALSE, tx.execute(s -> service.verify("host", 1, retired, sign(pair, "host", 1, retired))));
    }

    private static byte[] sign(KeyPair pair, String id, long generation, String challenge) {
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(pair.getPrivate());
            signature.update(AgentBoundKeyService.signingInput(id, generation, challenge));
            return signature.sign();
        } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    private static byte[] signBinding(KeyPair pair, String id, long generation, String token, byte[] key) {
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(pair.getPrivate());
            signature.update(AgentBoundKeyService.bindingSigningInput(id, generation, token, key));
            return signature.sign();
        } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
}
