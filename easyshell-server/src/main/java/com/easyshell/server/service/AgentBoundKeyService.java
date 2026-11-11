package com.easyshell.server.service;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Unwired primitives, not an authentication endpoint. Callers must authorize token issuance.
 * All operations require a caller transaction; exceptions must not be swallowed before rollback.
 * Signing input is UTF-8: easyshell-agent-proof-v1, agent ID, decimal generation and challenge,
 * each separated by a newline. Keys are canonical X.509 SubjectPublicKeyInfo Ed25519 bytes.
 * Binding input is UTF-8: easyshell-agent-bind-v1, agent ID, decimal generation,
 * lowercase SHA-256 hex of the ASCII enrollment token, and standard Base64 of the
 * canonical public key, each separated by a newline. No trailing newline is included.
 */
@Service
public class AgentBoundKeyService {
    static final int MAX_OUTSTANDING_CHALLENGES = 10;
    private final JdbcTemplate jdbc;
    private final AgentEnrollmentTokenService tokens;
    private final SecureRandom random = new SecureRandom();

    public AgentBoundKeyService(JdbcTemplate jdbc, AgentEnrollmentTokenService tokens) {
        this.jdbc = jdbc;
        this.tokens = tokens;
    }

    /** The first binding for a generation is immutable; a duplicate aborts the transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean bind(String agentId, long generation, String token, byte[] encodedKey, byte[] signature) {
        PublicKey key = parseKey(encodedKey);
        if (key == null || token == null || !token.matches("[A-Za-z0-9_-]{43}")
                || signature == null || signature.length != 64 || !validAgent(agentId, generation)) return false;
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(bindingSigningInput(agentId, generation, token, key.getEncoded()));
            if (!verifier.verify(signature)) return false;
        } catch (java.security.GeneralSecurityException e) {
            return false;
        }
        if (!lockActive(agentId, generation)) return false;
        if (!tokens.consume(token, agentId, generation)) return false;
        jdbc.update("INSERT INTO agent_bound_key (agent_id, generation, public_key) VALUES (?, ?, ?)",
                agentId, generation, Base64.getEncoder().encodeToString(key.getEncoded()));
        return true;
    }

    /** Returns a random one-use challenge. Only its SHA-256 digest is stored. */
    @Transactional(propagation = Propagation.MANDATORY)
    public String challenge(String agentId, long generation, Instant expiresAt) {
        if (expiresAt == null || !lockActive(agentId, generation) || key(agentId, generation) == null) {
            throw new IllegalArgumentException("Invalid challenge request");
        }
        Timestamp databaseTime = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", Timestamp.class);
        if (databaseTime == null || !expiresAt.isAfter(databaseTime.toInstant())
                || expiresAt.isAfter(databaseTime.toInstant().plusSeconds(300))) {
            throw new IllegalArgumentException("Invalid challenge request");
        }
        // A locking read sees committed rows even under MySQL's REPEATABLE READ isolation.
        List<String> outstanding = jdbc.query("SELECT challenge_hash FROM agent_key_challenge "
                + "WHERE agent_id = ? AND consumed_at IS NULL AND expires_at > CURRENT_TIMESTAMP(6) "
                + "LIMIT ? FOR UPDATE", (rs, n) -> rs.getString(1), agentId, MAX_OUTSTANDING_CHALLENGES);
        if (outstanding.size() >= MAX_OUTSTANDING_CHALLENGES) {
            throw new IllegalStateException("Too many outstanding challenges");
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        if (jdbc.update("INSERT INTO agent_key_challenge (challenge_hash, agent_id, generation, expires_at) VALUES (?, ?, ?, ?)",
                digest(challenge), agentId, generation, Timestamp.from(expiresAt)) != 1) {
            throw new IllegalStateException("Challenge was not stored");
        }
        return challenge;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean verify(String agentId, long generation, String challenge, byte[] signature) {
        if (challenge == null || !challenge.matches("[A-Za-z0-9_-]{43}") || signature == null
                || signature.length != 64 || !lockActive(agentId, generation)) return false;
        PublicKey key = key(agentId, generation);
        if (key == null) return false;
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(signingInput(agentId, generation, challenge));
            if (!verifier.verify(signature)) return false;
        } catch (java.security.GeneralSecurityException e) {
            return false;
        }
        return jdbc.update("UPDATE agent_key_challenge SET consumed_at = CURRENT_TIMESTAMP(6) "
                + "WHERE challenge_hash = ? AND agent_id = ? AND generation = ? "
                + "AND consumed_at IS NULL AND expires_at > CURRENT_TIMESTAMP(6)",
                digest(challenge), agentId, generation) == 1;
    }

    public static byte[] signingInput(String agentId, long generation, String challenge) {
        return ("easyshell-agent-proof-v1\n" + agentId + "\n" + generation + "\n" + challenge)
                .getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] bindingSigningInput(String agentId, long generation, String token, byte[] encodedKey) {
        if (!validAgent(agentId, generation) || token == null || !token.matches("[A-Za-z0-9_-]{43}")
                || parseKey(encodedKey) == null) throw new IllegalArgumentException("Invalid binding parameters");
        return ("easyshell-agent-bind-v1\n" + agentId + "\n" + generation + "\n" + digest(token)
                + "\n" + Base64.getEncoder().encodeToString(encodedKey)).getBytes(StandardCharsets.UTF_8);
    }

    private boolean lockActive(String agentId, long generation) {
        if (!validAgent(agentId, generation)) return false;
        List<String> rows = jdbc.query("SELECT agent_id FROM host_identity WHERE agent_id = ? "
                + "AND generation = ? AND active = 1 FOR UPDATE", (rs, n) -> rs.getString(1), agentId, generation);
        return rows.size() == 1 && agentId.equals(rows.get(0));
    }

    private static boolean validAgent(String agentId, long generation) {
        return agentId != null && !agentId.isBlank() && agentId.length() <= 64 && agentId.indexOf('\n') < 0
                && agentId.indexOf('\r') < 0 && generation > 0;
    }

    private PublicKey key(String agentId, long generation) {
        List<String> rows = jdbc.query("SELECT public_key FROM agent_bound_key WHERE agent_id = ? AND generation = ?",
                (rs, n) -> rs.getString(1), agentId, generation);
        if (rows.size() != 1) return null;
        try { return parseKey(Base64.getDecoder().decode(rows.get(0))); }
        catch (IllegalArgumentException e) { return null; }
    }

    private static PublicKey parseKey(byte[] encoded) {
        if (encoded == null || encoded.length != 44) return null;
        try {
            PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
            return java.util.Arrays.equals(encoded, key.getEncoded()) ? key : null;
        } catch (java.security.GeneralSecurityException e) { return null; }
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
