package com.easyshell.server.workflow.node;

import com.easyshell.server.model.entity.WorkflowApproval;
import com.easyshell.server.workflow.approval.WorkflowApprovalStore;
import com.easyshell.server.workflow.engine.NodeContext;
import com.easyshell.server.workflow.engine.NodeExecutor;
import com.easyshell.server.workflow.engine.NodeFailedException;
import com.easyshell.server.workflow.engine.NodeOutcome;
import com.easyshell.server.workflow.expression.ExpressionEvaluator;
import com.easyshell.server.workflow.graph.ValidationIssue;
import com.easyshell.server.workflow.model.NodeType;
import com.easyshell.server.workflow.model.WorkflowDefinition;
import com.easyshell.server.workflow.model.WorkflowNode;
import com.easyshell.server.workflow.properties.WorkflowProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * approval 节点执行器（含 {@code config.mode=human-confirm} 变体，§3.8）。
 *
 * <p><b>为什么不能用 {@code PlanConfirmationManager}</b>：它是双 ConcurrentHashMap、
 * key 维度 sessionId——SSE 断开 → future 永不 complete、线程池被占；服务重启 → Map 清空；
 * 审批人≠发起人 → key 天然绑定发起人会话；跨机器审批 → 不可能。工作流要求 waiting 必须
 * 释放线程、不依赖任何连接、可跨天跨机器恢复，故 DB 行唯一事实源 + 同 JVM 事件 +
 * 10s 扫描兜底，且不改旧类。</p>
 *
 * <p><b>human-confirm</b>：设计文档把它列在 M2（§1.2 N4 注），本实现按任务要求纳入 M1——
 * 它是 approval 的<b>配置变体</b>而非新节点类型：复用同一张审批行、同一套挂起/恢复状态机，
 * 差别只在默认值（onTimeout 默认 approve、requireComment 默认 false、标题前缀"人工确认"）。
 * 不新建表、不新增状态、不加第二套审批逻辑。</p>
 */
@Slf4j
@Component
public class ApprovalNodeExecutor implements NodeExecutor {

    private static final Set<String> APPROVER_ROLES = Set.of("ADMIN", "SUPER_ADMIN");
    private static final Set<String> ON_TIMEOUT = Set.of("approve", "reject", "fail");
    private static final int MIN_TIMEOUT_SEC = 60;
    private static final int MAX_TIMEOUT_SEC = 604800;

    private final WorkflowApprovalStore approvalStore;
    private final ExpressionEvaluator expressionEvaluator;
    private final WorkflowProperties properties;

    public ApprovalNodeExecutor(WorkflowApprovalStore approvalStore,
                                ExpressionEvaluator expressionEvaluator,
                                WorkflowProperties properties) {
        this.approvalStore = approvalStore;
        this.expressionEvaluator = expressionEvaluator;
        this.properties = properties;
    }

    @Override
    public NodeType supports() {
        return NodeType.APPROVAL;
    }

    @Override
    public void validate(WorkflowNode node, WorkflowDefinition def, ValidationIssue.Sink issues) {
        if (node.configString("title") == null || node.configString("title").isBlank()) {
            issues.error("NODE_CONFIG_INVALID", "approval 节点缺少 title", node.getKey());
        }
        String role = node.configString("approverRole", "ADMIN");
        if (!APPROVER_ROLES.contains(role)) {
            issues.error("NODE_CONFIG_INVALID",
                    "approverRole 必须是 ADMIN / SUPER_ADMIN，实际 " + role, node.getKey());
        }
        String onTimeout = node.configString("onTimeout", "reject");
        if (!ON_TIMEOUT.contains(onTimeout)) {
            issues.error("NODE_CONFIG_INVALID",
                    "onTimeout 必须是 approve / reject / fail，实际 " + onTimeout, node.getKey());
        }
        int timeoutSec = node.configInt("timeoutSec", properties.getApproval().getDefaultTimeoutSec());
        if (timeoutSec < MIN_TIMEOUT_SEC || timeoutSec > MAX_TIMEOUT_SEC) {
            issues.error("NODE_CONFIG_INVALID",
                    "timeoutSec 必须在 " + MIN_TIMEOUT_SEC + ".." + MAX_TIMEOUT_SEC + " 之间，实际 " + timeoutSec,
                    node.getKey());
        }
    }

    @Override
    public NodeOutcome execute(NodeContext ctx) {
        WorkflowNode node = ctx.node();
        String runId = ctx.run().getId();

        // resume 语义：审批行已存在 → 按决策收尾（不新建第二个审批行）
        Optional<WorkflowApproval> existing = approvalStore.findByRunAndNode(runId, node.getKey());
        if (existing.isPresent()) {
            return completeByDecision(ctx, existing.get());
        }

        boolean humanConfirm = "human-confirm".equals(node.configString("mode", "approval"));
        String title = node.configString("title");
        String renderedTitle = expressionEvaluator.renderTemplate(title,
                stringMap(node.getConfig() == null ? null : node.getConfig().get("parameters")),
                ctx.resolver(), ctx.variables()::addWarning);
        String detailTemplate = node.configString("detailTemplate");
        String detailJson = detailTemplate == null || detailTemplate.isBlank()
                ? defaultDetail(ctx)
                : expressionEvaluator.renderTemplate(detailTemplate,
                        stringMap(node.getConfig() == null ? null : node.getConfig().get("parameters")),
                        ctx.resolver(), ctx.variables()::addWarning);

        int timeoutSec = node.configInt("timeoutSec", properties.getApproval().getDefaultTimeoutSec());
        timeoutSec = Math.min(timeoutSec, properties.getApproval().getMaxWaitSeconds());
        WorkflowApproval approval = approvalStore.create(runId, node.getKey(), NodeType.APPROVAL.code(),
                (humanConfirm ? "[人工确认] " : "") + renderedTitle, detailJson, null, null,
                node.configString("approverRole", "ADMIN"), timeoutSec);
        ctx.nodeRun().setApprovalId(approval.getId());
        ctx.emit("APPROVAL_REQUESTED", "等待审批 approval=" + approval.getId());
        return NodeOutcome.WAITING;
    }

    /** 审批决策 → 节点终态（resume 时调用；decision/comment/decidedBy 进 output，§2.11） */
    private NodeOutcome completeByDecision(NodeContext ctx, WorkflowApproval approval) {
        WorkflowNode node = ctx.node();
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("decision", approval.getStatus());
        output.put("comment", approval.getDecisionComment());
        output.put("decidedBy", approval.getDecidedBy());
        switch (approval.getStatus()) {
            case "approved" -> {
                ctx.variables().putNodeOutput(node.getKey(), node.getOutputVar(), output);
                return NodeOutcome.SUCCEEDED;
            }
            case "rejected" -> throw new NodeFailedException("approval_rejected",
                    "审批被拒绝: " + approval.getDecisionComment());
            case "timeout" -> {
                String onTimeout = node.configString("onTimeout", "reject");
                if ("approve".equals(onTimeout)) {
                    ctx.variables().putNodeOutput(node.getKey(), node.getOutputVar(), output);
                    return NodeOutcome.SUCCEEDED;
                }
                throw new NodeFailedException("approval_timeout",
                        "审批超时（onTimeout=" + onTimeout + "）");
            }
            case "cancelled" -> throw new NodeFailedException("approval_cancelled",
                    "审批已被取消（运行终止）");
            default -> throw new NodeFailedException("APPROVAL_PENDING",
                    "审批行状态异常: " + approval.getStatus());
        }
    }

    /** detail_json 默认内容（不可变快照，创建时渲染一次，§7.1.2） */
    private String defaultDetail(NodeContext ctx) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("runId", ctx.run().getId());
        detail.put("nodeKey", ctx.node().getKey());
        detail.put("nodeName", ctx.node().getName());
        detail.put("triggeredBy", ctx.run().getStartedBy());
        detail.put("triggeredAt", java.time.LocalDateTime.now().toString());
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(detail);
        } catch (Exception e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item == null ? null : String.valueOf(item)));
        return result;
    }
}
