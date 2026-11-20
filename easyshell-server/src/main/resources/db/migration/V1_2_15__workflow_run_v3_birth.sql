-- Only newly created runs may receive a birth marker; historical runs remain unknown.
CREATE TABLE workflow_run_v3_birth (
    run_id VARCHAR(64) NOT NULL,
    version_id BIGINT NOT NULL,
    definition_sha256 CHAR(64) NOT NULL,
    semantics VARCHAR(32) NOT NULL,
    PRIMARY KEY (run_id)
) ENGINE=InnoDB;
