-- Standalone enrollment ledger; existing agent and identity data are unchanged.
CREATE TABLE agent_enrollment_token (
    token_hash CHAR(64) NOT NULL,
    agent_id VARCHAR(64) NOT NULL,
    generation BIGINT NOT NULL,
    initiated_by BIGINT NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    consumed_at TIMESTAMP(6) NULL,
    PRIMARY KEY (token_hash),
    KEY idx_agent_enrollment_active (agent_id, consumed_at)
) ENGINE=InnoDB;
