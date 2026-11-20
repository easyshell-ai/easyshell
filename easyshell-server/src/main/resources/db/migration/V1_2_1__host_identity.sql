-- Independent of Hibernate-managed agent: also valid on an empty database.
-- Rows are permanent tombstones; never delete or reset a generation.
CREATE TABLE host_identity (
    agent_id VARCHAR(64) NOT NULL,
    generation BIGINT NOT NULL DEFAULT 0,
    active TINYINT NOT NULL DEFAULT 0,
    acl_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (agent_id)
) ENGINE=InnoDB;
