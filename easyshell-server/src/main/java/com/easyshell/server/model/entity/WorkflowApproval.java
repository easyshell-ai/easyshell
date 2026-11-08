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
@Table(name = "workflow_approval", indexes = {
    @Index(name = "idx_appr_run", columnList = "run_id"),
    @Index(name = "idx_appr_status", columnList = "status"),
    @Index(name = "idx_appr_expires", columnList = "expires_at")
})
public class WorkflowApproval extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 软关联 workflow_run.id（零外键） */
    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;

    @Column(name = "node_key", nullable = false, length = 64)
    private String nodeKey;

    @Column(name = "node_type", nullable = false, length = 32)
    private String nodeType;

    @Column(nullable = false, length = 256)
    private String title;

    /** 不可变快照：创建时渲染一次，决策时不更新（§7.1.2） */
    @Lob
    @Column(name = "detail_json", columnDefinition = "MEDIUMTEXT")
    private String detailJson;

    @Column(name = "risk_level", length = 16)
    private String riskLevel;

    /** 只存模式串，禁止存命令原文（B2 #13 / C-9） */
    @Column(name = "risk_matches", length = 1024)
    private String riskMatches;

    /**
     * pending -> approved | rejected | timeout | cancelled
     */
    @Column(nullable = false, length = 16)
    private String status = "pending";

    @Column(name = "approver_role", nullable = false, length = 32)
    private String approverRole = "ADMIN";

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decision_comment", length = 512)
    private String decisionComment;

    /** 到点由 WorkflowWatchdog 置 timeout（默认 86400s，节点 timeoutSec 可覆盖） */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    /** 乐观锁：决策的条件 UPDATE 以它为竞争条件，重复决策返回 0 行 */
    @Version
    @Column(nullable = false)
    private Integer version = 0;
}
