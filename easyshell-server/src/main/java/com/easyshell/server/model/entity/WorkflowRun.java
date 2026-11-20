package com.easyshell.server.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "workflow_run", uniqueConstraints = {
    @UniqueConstraint(name = "uk_wfr_idem", columnNames = {"started_by", "idempotency_key"})
}, indexes = {
    @Index(name = "idx_wfr_workflow", columnList = "workflow_id"),
    @Index(name = "idx_wfr_status", columnList = "status"),
    @Index(name = "idx_wfr_started_at", columnList = "started_at"),
    @Index(name = "idx_wfr_owner_heartbeat", columnList = "owner_instance, heartbeat_at")
})
public class WorkflowRun extends BaseEntity {

    @Id
    @Column(length = 64)
    private String id;

    /** 软关联 workflow.id（零外键） */
    @Column(name = "workflow_id", nullable = false)
    private Long workflowId;

    /** 软关联 workflow_version.id（零外键），不可变版本，复现的基准 */
    @Column(name = "version_id", nullable = false)
    private Long versionId;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    /**
     * manual: 手动触发, scheduled: 定时（M2）, replay: 重放（M3）
     */
    @Column(name = "trigger_type", nullable = false, length = 16)
    private String triggerType = "manual";

    /**
     * pending -> running -> {waiting -> running} -> succeeded | failed | cancelled | timeout；
     * queued -> running；interrupted -> running（收养，M2）| failed（放弃，M1）
     */
    @Column(nullable = false, length = 16)
    private String status = "pending";

    /** 触发入参全量快照；不含渲染后脚本全文（§7.2.1 要素②） */
    @Lob
    @Column(name = "input_json", columnDefinition = "MEDIUMTEXT")
    private String inputJson;

    @Lob
    @Column(name = "output_json", columnDefinition = "MEDIUMTEXT")
    private String outputJson;

    @Column(name = "error_message", length = 1024)
    private String errorMessage;

    /** 波末 checkpoint 的标量变量全量快照（上限 512KB，超出截断并置 variablesTruncated） */
    @Lob
    @Column(name = "variables_json", columnDefinition = "MEDIUMTEXT")
    private String variablesJson;

    /** 变量快照被截断时置 1，被截断的变量按"不存在"处理（G-10） */
    @Column(name = "variables_truncated", nullable = false)
    private Boolean variablesTruncated = false;

    /** 当前波正在执行的 nodeKey 列表（512 字符快照，监控展示用） */
    @Column(name = "current_nodes", length = 512)
    private String currentNodes;

    /** 取消请求标记：置位后下一检查点传播取消（B10） */
    @Column(name = "cancel_requested", nullable = false)
    private Boolean cancelRequested = false;

    /** 单执行者标识，M1 启动自检多实例用（G-20） */
    @Column(name = "owner_instance", length = 128)
    private String ownerInstance;

    @Column(name = "heartbeat_at")
    private LocalDateTime heartbeatAt;

    /** 幂等键，与 started_by 组成 uk_wfr_idem；重复触发返回首个 runId */
    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    /** run 开始时的风险规则集合快照（SF-17：只存集合，不存 CommandRisk） */
    @Lob
    @Column(name = "risk_snapshot_json", columnDefinition = "MEDIUMTEXT")
    private String riskSnapshotJson;

    @Column(name = "started_by")
    private Long startedBy;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "duration_ms")
    private Long durationMs;
}
