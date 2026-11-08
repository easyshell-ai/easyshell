-- 1.2.0 工作流引擎数据层（设计文档 §5.2「M1 五张」，SF-19：只建 M1 字段）。
-- workflow_trigger / workflow_artifact 按 SF-19 属 M2，本版本不建。
--
-- 三条硬约束（MF-6 / MF-7）：
--   ① 只新增表，绝不 ALTER / DROP 任何存量表——回退 = 停服 DROP 本脚本的五张表 + 隐藏路由；
--   ② 全软关联、零外键（与 Job.taskId 风格一致），workflow_node_run.task_id 等列不建 FK；
--   ③ workflow_run / workflow_node_run / workflow_approval / audit_log 永久保留，禁止未来加自动清理。
--
-- 方言取舍：同一份脚本在 MySQL 8 与 H2(MODE=MySQL) 上均已实测可跑——
--   ENGINE=InnoDB / DEFAULT CHARSET=utf8mb4 在 H2(MODE=MySQL) 下被安全忽略；
--   AUTO_INCREMENT / TINYINT / DATETIME / MEDIUMTEXT / CHAR(64) / UNIQUE KEY / KEY 两边通用；
--   布尔语义列一律 TINYINT NOT NULL DEFAULT 0（与存量实体的 Boolean 映射一致）；
--   时间列不设 DEFAULT CURRENT_TIMESTAMP——由 JPA Auditing（BaseEntity）在应用侧写入。
-- 注：H2 驱动把 MEDIUMTEXT 报成 VARCHAR，故 Hibernate 自带的 ddl-auto=validate 无法在 H2 上
-- 校验 @Lob 列（方言伪差异）；实体↔DDL 的逐字段比对由 WorkflowSchemaConsistencyChecker 落地。

CREATE TABLE workflow (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    category VARCHAR(50),
    status VARCHAR(16) NOT NULL,
    visibility VARCHAR(16) NOT NULL,
    source VARCHAR(32),
    created_by BIGINT NOT NULL,
    current_version_id BIGINT,
    run_count INT NOT NULL DEFAULT 0,
    success_count INT NOT NULL DEFAULT 0,
    last_run_at DATETIME,
    created_at DATETIME,
    updated_at DATETIME,
    PRIMARY KEY (id),
    KEY idx_wf_category (category),
    KEY idx_wf_status_visibility (status, visibility),
    KEY idx_wf_created_by (created_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- workflow_version 不可变：发布后本行永不更新（定义指纹的唯一书面载体，§7.2.1 要素①）
CREATE TABLE workflow_version (
    id BIGINT NOT NULL AUTO_INCREMENT,
    workflow_id BIGINT NOT NULL,
    version INT NOT NULL,
    base_version INT NOT NULL DEFAULT 0,
    definition_json MEDIUMTEXT NOT NULL,
    definition_sha256 CHAR(64) NOT NULL,
    changelog VARCHAR(512),
    is_valid TINYINT NOT NULL DEFAULT 0,
    estimated_risk VARCHAR(16),
    published_at DATETIME,
    published_by BIGINT,
    created_at DATETIME,
    updated_at DATETIME,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wv_workflow_version (workflow_id, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- uk_wfr_idem 的 userId 维度取 started_by（设计 §3.5 幂等①：key 必须带 userId 维度）
CREATE TABLE workflow_run (
    id VARCHAR(64) NOT NULL,
    workflow_id BIGINT NOT NULL,
    version_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    trigger_type VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    input_json MEDIUMTEXT,
    output_json MEDIUMTEXT,
    error_message VARCHAR(1024),
    variables_json MEDIUMTEXT,
    variables_truncated TINYINT NOT NULL DEFAULT 0,
    current_nodes VARCHAR(512),
    cancel_requested TINYINT NOT NULL DEFAULT 0,
    owner_instance VARCHAR(128),
    heartbeat_at DATETIME,
    idempotency_key VARCHAR(128),
    risk_snapshot_json MEDIUMTEXT,
    started_by BIGINT,
    started_at DATETIME,
    finished_at DATETIME,
    duration_ms BIGINT,
    created_at DATETIME,
    updated_at DATETIME,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wfr_idem (started_by, idempotency_key),
    KEY idx_wfr_workflow (workflow_id),
    KEY idx_wfr_status (status),
    KEY idx_wfr_started_at (started_at),
    KEY idx_wfr_owner_heartbeat (owner_instance, heartbeat_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- uk_node_run 是 attempt 抢占竞态（MF-10）的兜底：startAttempt 靠它的重复键异常判定"已被接管"
CREATE TABLE workflow_node_run (
    id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    node_key VARCHAR(64) NOT NULL,
    node_type VARCHAR(32) NOT NULL,
    attempt INT NOT NULL DEFAULT 1,
    status VARCHAR(16) NOT NULL,
    input_json MEDIUMTEXT,
    output_json MEDIUMTEXT,
    output_sha256 CHAR(64),
    preview_sha256 CHAR(64),
    raw_params VARCHAR(256),
    artifact_ids VARCHAR(512),
    error_message VARCHAR(1024),
    warning_message VARCHAR(1024),
    agent_versions MEDIUMTEXT,
    cancel_delivered TINYINT NOT NULL DEFAULT 0,
    task_id VARCHAR(64),
    approval_id BIGINT,
    started_at DATETIME,
    finished_at DATETIME,
    duration_ms BIGINT,
    created_at DATETIME,
    updated_at DATETIME,
    PRIMARY KEY (id),
    UNIQUE KEY uk_node_run (run_id, node_key, attempt),
    KEY idx_wnr_run (run_id),
    KEY idx_wnr_status (status),
    KEY idx_wnr_task (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- detail_json 创建时渲染一次并永久不可变（§7.1.2）；version 为乐观锁列
CREATE TABLE workflow_approval (
    id BIGINT NOT NULL AUTO_INCREMENT,
    run_id VARCHAR(64) NOT NULL,
    node_key VARCHAR(64) NOT NULL,
    node_type VARCHAR(32) NOT NULL,
    title VARCHAR(256) NOT NULL,
    detail_json MEDIUMTEXT,
    risk_level VARCHAR(16),
    risk_matches VARCHAR(1024),
    status VARCHAR(16) NOT NULL,
    approver_role VARCHAR(32) NOT NULL DEFAULT 'ADMIN',
    decided_by BIGINT,
    decided_at DATETIME,
    decision_comment VARCHAR(512),
    expires_at DATETIME,
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME,
    updated_at DATETIME,
    PRIMARY KEY (id),
    KEY idx_appr_run (run_id),
    KEY idx_appr_status (status),
    KEY idx_appr_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
