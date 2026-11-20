package com.easyshell.server.model.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 审批出站视图（§6.1 GET /approvals/pending、POST /approvals/{approvalId}/decision）。
 *
 * <p>实体上 {@code detailJson} 是不可变快照文本列、{@code decisionComment} 是决策备注，
 * 且<b>没有</b> workflow 维度——审批中心要在列表里显示"哪个工作流的哪个节点"，
 * 只能靠 {@code runId} 反查。本 VO 把这三样补齐：{@code detail} 解析成对象、
 * {@code reason} 暴露决策备注、{@code workflowId}/{@code workflowName} 由 run 反查回填。</p>
 *
 * <p>{@code riskMatches} 只存模式串（B2 #13 / C-9：禁止存命令原文），原样透出。</p>
 */
@Data
@Builder
public class WorkflowApprovalVO {

    private Long id;

    private String runId;

    /** 由 run.workflowId 反查回填（审批中心列表要显示工作流名） */
    private Long workflowId;

    private String workflowName;

    private String nodeKey;

    /** approval / human-confirm */
    private String nodeType;

    private String title;

    /** 创建时渲染一次的不可变快照（detail_json 解析） */
    private Map<String, Object> detail;

    /** LOW / MEDIUM / HIGH / BANNED */
    private String riskLevel;

    /** 只存模式串，禁止存命令原文 */
    private String riskMatches;

    /** pending / approved / rejected / timeout / cancelled */
    private String status;

    private String approverRole;

    private Long decidedBy;

    private LocalDateTime decidedAt;

    /** 决策备注（实体列 decisionComment） */
    private String reason;

    private LocalDateTime expiresAt;

    private LocalDateTime createdAt;
}
