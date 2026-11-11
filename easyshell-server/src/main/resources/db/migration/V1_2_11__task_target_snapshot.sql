-- Independent of Hibernate-managed task and agent tables; historical tasks remain unknown.
CREATE TABLE task_target_snapshot (
    task_id VARCHAR(64) NOT NULL,
    target_count INT NOT NULL,
    completed TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (task_id)
) ENGINE=InnoDB;

CREATE TABLE task_target_snapshot_item (
    task_id VARCHAR(64) NOT NULL,
    agent_id VARCHAR(64) NOT NULL,
    generation BIGINT NOT NULL,
    PRIMARY KEY (task_id, agent_id),
    CONSTRAINT fk_task_target_snapshot_item FOREIGN KEY (task_id)
        REFERENCES task_target_snapshot (task_id)
) ENGINE=InnoDB;
