package com.easyshell.server.security;

import io.jsonwebtoken.Claims;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Validation primitive for generation-bound agent credentials.
 * Callers must not fall back to legacy agent authentication when this validation fails. A successful
 * check is a point-in-time result; operations needing atomicity with retirement must lock and recheck
 * the host identity in their own transaction. No credential issuance or migration is enabled here.
 */
@Component
public class AgentCredentialGenerationValidator {
    public static final String TOKEN_TYPE_BOUND_AGENT = "bound-agent";
    public static final String GENERATION_CLAIM = "host_generation";

    private final JwtUtils jwt;
    private final JdbcTemplate jdbc;

    public AgentCredentialGenerationValidator(JwtUtils jwt, JdbcTemplate jdbc) {
        this.jwt = jwt;
        this.jdbc = jdbc;
    }

    public Optional<Identity> validate(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        try {
            Claims claims = jwt.parseToken(token); // verifies signature and expiry
            String agentId = claims.getSubject();
            Object value = claims.get(GENERATION_CLAIM);
            if (!TOKEN_TYPE_BOUND_AGENT.equals(claims.get("type")) || claims.getExpiration() == null
                    || claims.getExpiration().getTime() <= System.currentTimeMillis() || agentId == null || agentId.isBlank()
                    || agentId.length() > 64 || !(value instanceof Integer || value instanceof Long)) {
                return Optional.empty();
            }
            long generation = ((Number) value).longValue();
            if (generation <= 0) return Optional.empty();
            List<Identity> matches = jdbc.query(
                    "SELECT agent_id, generation FROM host_identity WHERE agent_id = ? AND generation = ? AND active = 1",
                    (rs, row) -> new Identity(rs.getString(1), rs.getLong(2)), agentId, generation);
            // Database collations can be case-insensitive; identity comparisons must not be.
            if (matches.size() != 1 || !agentId.equals(matches.get(0).agentId())) return Optional.empty();
            return Optional.of(matches.get(0));
        } catch (Exception e) {
            // Invalid credentials and an unavailable identity ledger both fail closed.
            return Optional.empty();
        }
    }

    /** A database failure must never permit a downgrade to a legacy credential. */
    public boolean permitsLegacy(String agentId) {
        return permitsLegacy(agentId, false);
    }

    /** Caller holds the host identity lock in the transaction that issues the token. */
    public boolean permitsLegacyLocked(String agentId) {
        return permitsLegacy(agentId, true);
    }

    private boolean permitsLegacy(String agentId, boolean locking) {
        if (agentId == null || agentId.isBlank() || agentId.length() > 64) return false;
        try {
            return jdbc.query("SELECT agent_id FROM agent_bound_key WHERE agent_id = ? LIMIT 1"
                            + (locking ? " FOR UPDATE" : ""),
                    (rs, row) -> rs.getString(1), agentId).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    public record Identity(String agentId, long generation) { }
}
