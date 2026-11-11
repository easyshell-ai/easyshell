-- Historical runs deliberately have no row and are therefore unknown.
CREATE TABLE workflow_run_provenance (
    run_id VARCHAR(64) NOT NULL,
    version_id BIGINT NOT NULL,
    definition_sha256 CHAR(64) NOT NULL,
    source_kind VARCHAR(16) NOT NULL,
    task_count INT NOT NULL,
    completed TINYINT NOT NULL,
    PRIMARY KEY (run_id)
) ENGINE=InnoDB;

CREATE TABLE workflow_run_provenance_task (
    run_id VARCHAR(64) NOT NULL,
    task_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (run_id, task_id),
    UNIQUE (task_id),
    CONSTRAINT fk_workflow_provenance_task FOREIGN KEY (run_id)
        REFERENCES workflow_run_provenance (run_id)
) ENGINE=InnoDB;
