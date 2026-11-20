-- Permanent claim tombstones, independent of legacy workflow tables.
CREATE TABLE workflow_recovery_run (
    run_id VARCHAR(64) NOT NULL,
    last_round BIGINT NOT NULL DEFAULT 0,
    active_round BIGINT NULL,
    PRIMARY KEY (run_id)
) ENGINE=InnoDB;

CREATE TABLE workflow_recovery_batch (
    run_id VARCHAR(64) NOT NULL,
    claim_round BIGINT NOT NULL,
    owner_id VARCHAR(128) NOT NULL,
    fencing_token VARCHAR(36) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    closed_at TIMESTAMP(6) NULL,
    PRIMARY KEY (run_id, claim_round),
    UNIQUE (fencing_token)
) ENGINE=InnoDB;

CREATE TABLE workflow_recovery_member (
    run_id VARCHAR(64) NOT NULL,
    claim_round BIGINT NOT NULL,
    node_run_id VARCHAR(64) NOT NULL,
    node_key VARCHAR(64) NOT NULL,
    attempt INT NOT NULL,
    claim_state VARCHAR(16) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (run_id, claim_round, node_run_id, attempt),
    UNIQUE (node_run_id, attempt),
    CONSTRAINT chk_recovery_state CHECK (claim_state IN
        ('CLAIMED', 'ENTERED', 'UNCERTAIN', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT chk_recovery_attempt CHECK (attempt > 0)
) ENGINE=InnoDB;
