-- No backfill: historical report provenance cannot be reconstructed safely.
CREATE TABLE ai_inspect_report_task_binding (
    report_id BIGINT NOT NULL PRIMARY KEY,
    task_id VARCHAR(64) NOT NULL UNIQUE,
    CONSTRAINT fk_inspect_binding_snapshot FOREIGN KEY (task_id) REFERENCES task_target_snapshot (task_id)
) ENGINE=InnoDB;
