package com.easyshell.server.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 单节点试运行结果（§6.1 POST /runs/{runId}/nodes/{nodeKey}/test-run）。
 *
 * <p>M1 的 test-run 是<b>不派发的预演</b>：完整走 §2.4.3 的六步管线（静态检查 → 求值 →
 * L1 → L2 → L3 → L4）并返回渲染结果与风险判定，但<b>不创建 Task/Job 行</b>——
 * 真派发需要 run 上下文与真实 agent，留给 M2 的 E2 完整版。renderedScript 只返回给
 * 工作流属主（端点本身过 requireWorkflowOwner）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowTestRunVO {

    private String nodeKey;
    private String nodeType;

    /** ok / blocked */
    private String status;

    private String riskLevel;
    private List<String> riskMatches;
    private String riskPolicy;

    /** 渲染后的脚本全文（L2 之后）；condition 节点为 null */
    private String renderedScript;

    /** 求值后的 parameters Map（不含渲染后全文之外的东西） */
    private Map<String, String> resolvedParameters;

    /** condition 节点的求值结果 */
    private Boolean conditionResult;

    /** 解析出的目标 agent 数（script-exec） */
    private Integer targetAgentCount;

    private List<String> warnings;
}
