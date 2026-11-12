package com.easyshell.server.repository;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Stores token digests only. All operations participate in the caller's transaction. */
@Repository
public class AgentEnrollmentTokenRepository {
    private final JdbcTemplate jdbc;

    public AgentEnrollmentTokenRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(String digest, String agentId, long generation, long initiatedBy, Instant expiresAt) {
        jdbc.update("INSERT INTO agent_enrollment_token (token_hash, agent_id, generation, initiated_by, expires_at) "
                + "VALUES (?, ?, ?, ?, ?)", digest, agentId, generation, initiatedBy, Timestamp.from(expiresAt));
    }

    /** Caller must hold the agent's HostIdentity row lock in the same transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void revokeUnconsumed(String agentId) {
        jdbc.update("UPDATE agent_enrollment_token SET consumed_at = CURRENT_TIMESTAMP(6) "
                + "WHERE agent_id = ? AND consumed_at IS NULL", agentId);
    }

    /** Conditional update is the single-use linearization point, even for concurrent consumers. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean consume(String digest, String agentId, long generation) {
        return jdbc.update("UPDATE agent_enrollment_token SET consumed_at = CURRENT_TIMESTAMP(6) "
                + "WHERE token_hash = ? AND agent_id = ? AND generation = ? "
                + "AND consumed_at IS NULL AND expires_at > CURRENT_TIMESTAMP(6)",
                digest, agentId, generation) == 1;
    }
}
