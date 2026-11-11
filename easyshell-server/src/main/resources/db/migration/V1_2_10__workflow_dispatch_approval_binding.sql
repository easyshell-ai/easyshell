-- Binding evidence only, not a second approval. Missing evidence never authorizes dispatch.
-- approval_id is a soft reference to workflow_approval.id, consistent with workflow tables.
CREATE TABLE workflow_dispatch_approval_binding (
    approval_id BIGINT NOT NULL PRIMARY KEY,
    dispatch_node_run_id VARCHAR(64) NOT NULL,
    dispatch_attempt INT NOT NULL,
    dispatch_user_id BIGINT NOT NULL,
    dispatch_script_hmac CHAR(64) NOT NULL,
    dispatch_intent_hmac CHAR(64) NOT NULL,
    dispatch_targets MEDIUMTEXT NOT NULL,
    dispatch_timeout_sec INT NOT NULL,
    dispatch_consumed_at DATETIME,
    dispatch_task_id VARCHAR(64)
);
