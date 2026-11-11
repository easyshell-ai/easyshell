-- Separate ledgers: no backfill or changes to existing agents and credentials.
CREATE TABLE agent_bound_key (
    agent_id VARCHAR(64) NOT NULL,
    generation BIGINT NOT NULL,
    public_key VARCHAR(128) NOT NULL,
    PRIMARY KEY (agent_id, generation)
) ENGINE=InnoDB;

CREATE TABLE agent_key_challenge (
    challenge_hash CHAR(64) NOT NULL,
    agent_id VARCHAR(64) NOT NULL,
    generation BIGINT NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    consumed_at TIMESTAMP(6) NULL,
    PRIMARY KEY (challenge_hash),
    KEY idx_agent_key_challenge_active (agent_id, consumed_at, expires_at)
) ENGINE=InnoDB;
