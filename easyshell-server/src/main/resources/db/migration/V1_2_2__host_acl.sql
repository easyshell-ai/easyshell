-- Independent tables only: agent and sys_user are created by Hibernate later.
CREATE TABLE host_acl_state (
    id INT NOT NULL PRIMARY KEY,
    completed TINYINT NOT NULL DEFAULT 0
) ENGINE=InnoDB;
INSERT INTO host_acl_state (id, completed) VALUES (1, 0);

CREATE TABLE host_acl_grant (
    agent_id VARCHAR(64) NOT NULL,
    generation BIGINT NOT NULL,
    subject_type VARCHAR(8) NOT NULL,
    subject_id VARCHAR(64) NOT NULL,
    permission VARCHAR(16) NOT NULL,
    PRIMARY KEY (agent_id, generation, subject_type, subject_id, permission)
) ENGINE=InnoDB;
