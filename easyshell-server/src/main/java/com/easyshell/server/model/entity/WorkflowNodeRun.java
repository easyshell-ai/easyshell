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
@Table(name = "workflow_node_run", uniqueConstraints = {
    @UniqueConstraint(name = "uk_node_run", columnNames = {"run_id", "node_key", "attempt"})
}, indexes = {
    @Index(name = "idx_wnr_run", columnList = "run_id"),
    @Index(name = "idx_wnr_status", columnList = "status"),
    @Index(name = "idx_wnr_task", columnList = "task_id")
})
public class WorkflowNodeRun extends BaseEntity {

    @Id
    @Column(length = 64)
    private String id;

    /** 软关联 workflow_run.id（零外键） */
    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;

    /** 匹配 ^[a-z][a-z0-9_]{0,63}$，与 outputVar 不得相同（发布期 ERROR） */
    @Column(name = "node_key", nullable = false, length = 64)
    private String nodeKey;

    /** start / script-exec / transform / condition / ai-analyze / llm / classifier / code / approval / notify / wait / end */
    @Column(name = "node_type", nullable = false, length = 32)
    private String nodeType;

    /** 从 1 开始，每次重试 +1；uk_node_run 兜底抢占竞态（MF-10） */
    @Column(nullable = false)
    private Integer attempt = 1;

    /**
     * pending -> running -> succeeded | failed | skipped | cancelled | timeout，
     * 外加 running -> waiting -> running（审批挂起）
     */
    @Column(nullable = false, length = 16)
    private String status = "pending";

    /** parameters Map + rawParams 声明；不含渲染后脚本全文（MF-13） */
    @Lob
    @Column(name = "input_json", columnDefinition = "MEDIUMTEXT")
    private String inputJson;

    @Lob
    @Column(name = "output_json", columnDefinition = "MEDIUMTEXT")
    private String outputJson;

    /** 完整内容字节流的 SHA-256；截断时输入 = 截断后 8192B（MF-13） */
    @Column(name = "output_sha256", length = 64, columnDefinition = "CHAR(64)")
    private String outputSha256;

    @Column(name = "preview_sha256", length = 64, columnDefinition = "CHAR(64)")
    private String previewSha256;

    /** 声明了 raw:true 的参数名清单（审计用） */
    @Column(name = "raw_params", length = 256)
    private String rawParams;

    /** 大输出 artifact 句柄（M2 启用，M1 恒空） */
    @Column(name = "artifact_ids", length = 512)
    private String artifactIds;

    @Column(name = "error_message", length = 1024)
    private String errorMessage;

    @Column(name = "warning_message", length = 1024)
    private String warningMessage;

    /** 派发时从 Agent.agentVersion 读取的快照 {agentId: version}（B12，零 Job 表改动） */
    @Lob
    @Column(name = "agent_versions", columnDefinition = "MEDIUMTEXT")
    private String agentVersions;

    /** cancel 是否已送达 agent（X4：1.2.x 立项前 agent 无 cancel 消息） */
    @Column(name = "cancel_delivered", nullable = false)
    private Boolean cancelDelivered = false;

    /** 软关联 task.id（零外键），script-exec 复用 createAndDispatch 的产物 */
    @Column(name = "task_id", length = 64)
    private String taskId;

    /** 软关联 workflow_approval.id（零外键），approval 节点挂起时回填 */
    @Column(name = "approval_id")
    private Long approvalId;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "duration_ms")
    private Long durationMs;
}
