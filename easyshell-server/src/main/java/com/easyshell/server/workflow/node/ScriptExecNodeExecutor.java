package com.easyshell.server.workflow.node;

import com.easyshell.server.ai.model.vo.RiskAssessment;
import com.easyshell.server.ai.risk.CommandRiskEngine;
import com.easyshell.server.ai.risk.RiskLevel;
import com.easyshell.server.ai.security.SensitiveDataFilter;
import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.dto.TaskCreateRequest;
import com.easyshell.server.model.entity.Agent;
import com.easyshell.server.model.entity.Job;
import com.easyshell.server.model.entity.Task;
import com.easyshell.server.model.entity.WorkflowApproval;
import com.easyshell.server.repository.AgentRepository;
import com.easyshell.server.repository.JobRepository;
import com.easyshell.server.repository.TaskRepository;
import com.easyshell.server.repository.WorkflowApprovalRepository;
import com.easyshell.server.service.ClusterService;
import com.easyshell.server.service.DispatchAuthority;
import com.easyshell.server.service.TagService;
import com.easyshell.server.service.TaskService;
import com.easyshell.server.workflow.approval.WorkflowApprovalStore;
import com.easyshell.server.workflow.engine.NodeCancelledException;
import com.easyshell.server.workflow.engine.NodeContext;
import com.easyshell.server.workflow.engine.NodeExecutor;
import com.easyshell.server.workflow.engine.NodeFailedException;
import com.easyshell.server.workflow.engine.NodeOutcome;
import com.easyshell.server.workflow.engine.VariableStore;
import com.easyshell.server.workflow.expression.ExpressionEvaluator;
import com.easyshell.server.workflow.expression.ExpressionException;
import com.easyshell.server.workflow.expression.ShellSafeRenderer;
import com.easyshell.server.workflow.expression.SubstitutionGuard;
import com.easyshell.server.workflow.expression.WorkflowValuePolicy;
import com.easyshell.server.workflow.graph.DagValidator;
import com.easyshell.server.workflow.graph.ValidationIssue;
import com.easyshell.server.workflow.model.NodeType;
import com.easyshell.server.workflow.model.RenderResult;
import com.easyshell.server.workflow.model.WorkflowNode;
import com.easyshell.server.workflow.model.ValueType;
import com.easyshell.server.workflow.properties.WorkflowProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * script-exec 节点执行器（§2.4.3 六步管线 + §3.13 轮询协议 + §6.3.3 派发授权）。
 *
 * <p><b>严格顺序</b>（全部在 Task 行创建之前）：
 * ① 静态检查（占位符合规 + ⊆ parameters 键集合 + raw 声明一致）→
 * ② {@code ExpressionEvaluator} 求值 parameters 得纯字符串 Map →
 * ③ {@link WorkflowValuePolicy}（L1）→
 * ④ {@link ShellSafeRenderer}（L2）→
 * ⑤ {@link SubstitutionGuard}（L3）→
 * ⑥ {@link CommandRiskEngine}（L4，就在 {@code createAndDispatch} 咽喉里）→
 * ⑦ {@code createAndDispatch(rendered, ...)}（落库 + 派发）。</p>
 *
 * <p><b>派发授权（§6.3.3）</b>：默认无授权（走门禁，非 LOW 一律 449）。唯一例外是本 run 里
 * 某个<b>拓扑祖先</b> approval 节点已 approved（或本节点自己的 MEDIUM 动态审批已通过）——
 * 此时传 {@link DispatchAuthority#HUMAN_APPROVED}，证据 = {@code workflow_approval:<runId>:<id>}。
 * 没有 approval 祖先的工作流，HIGH 脚本永远节点失败（BANNED 同理，人工审批是唯一能放过
 * BANNED 的路径）。</p>
 */
@Slf4j
@Component
public class ScriptExecNodeExecutor implements NodeExecutor {

    /** 单参数值硬上限（§2.10 MAX_VALUE） */
    private static final int PARAM_VALUE_MAX_CHARS = 262144;

    private final TaskService taskService;
    private final ClusterService clusterService;
    private final TagService tagService;
    private final AgentRepository agentRepository;
    private final JobRepository jobRepository;
    private final TaskRepository taskRepository;
    private final WorkflowApprovalRepository approvalRepository;
    private final WorkflowApprovalStore approvalStore;
    private final ExpressionEvaluator expressionEvaluator;
    private final CommandRiskEngine riskEngine;
    private final DagValidator dagValidator;
    private final SensitiveDataFilter sensitiveDataFilter;
    private final ObjectMapper objectMapper;
    private final WorkflowProperties properties;

    /**
     * 派发事务边界：{@code createAndDispatch} 里要注册 afterCommit 回调（派发后置动作），
     * 而执行体跑在引擎池线程上没有事务——显式包一层，避免"Transaction synchronization
     * is not active"。
     */
    private final TransactionTemplate dispatchTransactionTemplate;

    public ScriptExecNodeExecutor(TaskService taskService,
                                  ClusterService clusterService,
                                  TagService tagService,
                                  AgentRepository agentRepository,
                                  JobRepository jobRepository,
                                  TaskRepository taskRepository,
                                  WorkflowApprovalRepository approvalRepository,
                                  WorkflowApprovalStore approvalStore,
                                  ExpressionEvaluator expressionEvaluator,
                                  CommandRiskEngine riskEngine,
                                  DagValidator dagValidator,
                                  SensitiveDataFilter sensitiveDataFilter,
                                  ObjectMapper objectMapper,
                                  WorkflowProperties properties,
                                  PlatformTransactionManager transactionManager) {
        this.taskService = taskService;
        this.clusterService = clusterService;
        this.tagService = tagService;
        this.agentRepository = agentRepository;
        this.jobRepository = jobRepository;
        this.taskRepository = taskRepository;
        this.approvalRepository = approvalRepository;
        this.approvalStore = approvalStore;
        this.expressionEvaluator = expressionEvaluator;
        this.riskEngine = riskEngine;
        this.dagValidator = dagValidator;
        this.sensitiveDataFilter = sensitiveDataFilter;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.dispatchTransactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public NodeType supports() {
        return NodeType.SCRIPT_EXEC;
    }

    @Override
    public void validate(WorkflowNode node,
                         com.easyshell.server.workflow.model.WorkflowDefinition def,
                         ValidationIssue.Sink issues) {
        // 占位符合规性 + raw 声明一致性（§2.3 契约② / §2.4.4 步骤 0）
        String script = node.configString("script");
        if (script == null || script.isBlank()) {
            issues.error("NODE_CONFIG_INVALID", "script-exec 节点缺少 script", node.getKey());
            return;
        }
        for (String loose : ShellSafeRenderer.looseSlots(script)) {
            if (!ShellSafeRenderer.isWellFormedSlot(loose)) {
                issues.error("PARAM_SLOT_MALFORMED",
                        "占位符 " + loose + " 不合规：NAME 必须匹配 ^[A-Z][A-Z0-9_]*$", node.getKey());
            }
        }
        Set<String> slots = ShellSafeRenderer.slotNames(script);
        Set<String> rawSlots = ShellSafeRenderer.rawSlotNames(script);
        Map<String, String> parameters = stringMap(node.getConfig() == null ? null : node.getConfig().get("parameters"));
        List<String> rawParams = stringList(node.getConfig() == null ? null : node.getConfig().get("rawParams"));
        for (String slot : slots) {
            if (!parameters.containsKey(slot)) {
                issues.error("PARAM_MISSING",
                        "脚本占位符 {{" + slot + "}} 没有对应的 parameters 取值", node.getKey());
            }
        }
        for (String raw : rawSlots) {
            if (!rawParams.contains(raw)) {
                issues.error("PARAM_RAW_UNDECLARED",
                        "占位符 {{" + raw + ":raw}} 未在 rawParams 中声明", node.getKey());
            }
        }
        for (String raw : rawParams) {
            if (!rawSlots.contains(raw)) {
                issues.warning("PARAM_RAW_UNUSED",
                        "rawParams 声明了 " + raw + "，但脚本里没有 {{" + raw + ":raw}}", node.getKey());
            }
        }
        // raw 参数强制 riskPolicy >= allow_medium（§2.4.4 raw 定位③）
        if (!rawParams.isEmpty()) {
            String policy = node.configString("riskPolicy", "block");
            if (!"allow_medium".equals(policy) && !"strict".equals(policy)) {
                issues.error("RAW_REQUIRES_ALLOW_MEDIUM",
                        "声明了 raw 参数的节点必须把 riskPolicy 至少设为 allow_medium", node.getKey());
            }
        }
        // targets 必填
        Map<String, Object> targets = node.configMap("targets");
        if (targets == null || targets.get("mode") == null) {
            issues.error("NODE_CONFIG_INVALID", "script-exec 节点缺少 targets.mode", node.getKey());
        } else if ("variable".equals(String.valueOf(targets.get("mode")))
                && (targets.get("variableRef") == null || String.valueOf(targets.get("variableRef")).isBlank())) {
            issues.error("NODE_CONFIG_INVALID",
                    "targets.mode=variable 时必须提供 variableRef", node.getKey());
        }
    }

    @Override
    public NodeOutcome execute(NodeContext ctx) {
        WorkflowNode node = ctx.node();
        String runId = ctx.run().getId();
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        String script = node.configString("script");
        if (script == null || script.isBlank()) {
            throw new NodeFailedException("NODE_CONFIG_INVALID", "script-exec 节点缺少 script");
        }
        Map<String, String> parameters = stringMap(config.get("parameters"));
        Set<String> rawParams = new LinkedHashSet<>(stringList(config.get("rawParams")));
        String onUnsafeValue = node.configString("onUnsafeValue", "reject");
        String riskPolicy = node.configString("riskPolicy", "block");

        // ① 静态检查：槽位格式 / 槽位 ⊆ parameters / raw 声明
        // 只有"像槽位但不合规格"的才拒绝（合规格的 {{MSG}} 不拦）
        for (String loose : ShellSafeRenderer.looseSlots(script)) {
            if (!ShellSafeRenderer.isWellFormedSlot(loose)) {
                throw new NodeFailedException("PARAM_SLOT_MALFORMED",
                        "占位符 " + loose + " 不合规：NAME 必须匹配 ^[A-Z][A-Z0-9_]*$");
            }
        }
        for (String slot : ShellSafeRenderer.slotNames(script)) {
            if (!parameters.containsKey(slot)) {
                throw new NodeFailedException("PARAM_MISSING", "缺少参数 " + slot + " 的取值");
            }
        }
        for (String rawSlot : ShellSafeRenderer.rawSlotNames(script)) {
            if (!rawParams.contains(rawSlot)) {
                throw new NodeFailedException("PARAM_RAW_UNDECLARED",
                        "占位符 {{" + rawSlot + ":raw}} 未在 rawParams 中声明");
            }
        }

        // ② 参数表达式求值（服务端先求值再进入渲染管线，§2.3 契约④）
        Map<String, String> values = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            String value;
            try {
                value = expressionEvaluator.resolveTemplateValue(entry.getValue(), ctx.resolver(),
                        ctx.variables()::addWarning);
            } catch (ExpressionException e) {
                throw new NodeFailedException(e.getCode(),
                        "参数 " + entry.getKey() + " 求值失败: " + e.getMessage());
            }
            values.put(entry.getKey(), value);
        }

        // ③ L1 值策略（含 onUnsafeValue 三处置，§2.4.5）
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            boolean raw = rawParams.contains(name);
            if (WorkflowValuePolicy.check(name, value, raw) == WorkflowValuePolicy.ValueDisposition.REJECT) {
                String disposition = switch (onUnsafeValue == null ? "reject" : onUnsafeValue) {
                    case "raw" -> raw ? "accept" : "reject";
                    case "truncate" -> "truncate";
                    default -> "reject";
                };
                switch (disposition) {
                    case "accept" -> {
                        // raw 声明过的参数：字符集由 L3 执法
                    }
                    case "truncate" -> {
                        int keep = safePrefixLength(value);
                        values.put(name, value.substring(0, keep));
                        ctx.variables().addWarning("参数 " + name + " 值越界，已截断到白名单边界（"
                                + keep + " 字符）");
                    }
                    default -> throw new NodeFailedException("PARAM_UNSAFE",
                            "参数 " + name + " 的值越出安全字符集（位置 "
                                    + WorkflowValuePolicy.firstOffendingChar(value) + "）");
                }
            }
        }

        // ④ L2 引用渲染（单引号包裹 + 区间只覆盖替换值）
        RenderResult rendered = ShellSafeRenderer.render(script, values, rawParams);

        // ⑤ L3 segment 级禁替换
        String reject = SubstitutionGuard.rejectSubstitution(rendered.rendered(), rendered.valueRanges());
        if (reject != null) {
            throw new NodeFailedException(reject, "参数值渲染结果被 L3 拒绝: " + reject);
        }

        // ⑥ L4 风险门禁预判 + riskPolicy 三态（真正的门禁在 createAndDispatch 里）
        RiskAssessment risk = riskEngine.assessScript(rendered.rendered());
        Optional<WorkflowApproval> riskAuthority = findApprovedAuthority(ctx);
        if (riskAuthority.isEmpty()) {
            RiskLevel level = risk.getOverallRisk();
            if (level == RiskLevel.BANNED) {
                throw new NodeFailedException("RISK_BLOCKED",
                        "脚本命中封禁模式: " + String.join(",", risk.getBannedMatches())
                                + "（仅人工审批可豁免）");
            }
            if (level == RiskLevel.HIGH) {
                throw new NodeFailedException("RISK_BLOCKED",
                        "高风险脚本需要本 run 内已通过的 approval 祖先节点授权（§6.3.3）");
            }
            if (level == RiskLevel.MEDIUM && !"allow_medium".equals(riskPolicy)) {
                throw new NodeFailedException("RISK_BLOCKED",
                        "中风险脚本被 riskPolicy=" + riskPolicy + " 拒绝；如需执行请改为 allow_medium"
                                + "（转人工审批）或降低脚本风险");
            }
            if (level == RiskLevel.MEDIUM) {
                // MEDIUM → 转审批：创建审批行后挂起，通过后以 HUMAN_APPROVED 派发（§6.5.3）
                WorkflowApproval approval = approvalStore.create(runId, node.getKey(),
                        NodeType.SCRIPT_EXEC.code(), "中风险脚本执行确认",
                        detailJson(ctx, level, risk), level.name(),
                        String.join(",", risk.getBannedMatches()),
                        "ADMIN", properties.getApproval().getDefaultTimeoutSec());
                ctx.nodeRun().setApprovalId(approval.getId());
                return NodeOutcome.WAITING;
            }
        }

        // ⑦ 目标解析（host-list 执行前校验，§2.7）
        Set<String> agentIds = resolveTargets(ctx, node);

        // ⑧ 派发授权（final 化以便进 lambda：默认无授权，唯一例外是已审批的 approval 祖先）
        Optional<WorkflowApproval> approvedAuthority = findApprovedAuthority(ctx);
        DispatchAuthority authority =
                approvedAuthority.isPresent() ? DispatchAuthority.HUMAN_APPROVED : null;
        String evidenceRef = approvedAuthority.isPresent()
                ? "workflow_approval:" + runId + ":" + approvedAuthority.get().getId() : null;

        TaskCreateRequest request = new TaskCreateRequest();
        request.setName(execTokenName(ctx));
        // 渲染后全文直接派发：绝不再传 parameters，否则 ScriptParameterUtils 会做第二次
        // 无引用裸替换，把 L1/L2/L3 全部绕开（§2.3 契约②的防护对象）
        request.setScriptContent(rendered.rendered());
        request.setAgentIds(new ArrayList<>(agentIds));
        request.setTimeoutSeconds(node.configInt("timeoutSeconds", 3600));

        ctx.nodeRun().setInputJson(inputJson(values, rawParams));
        ctx.nodeRun().setRawParams(String.join(",", rawParams));

        Task task = dispatchTransactionTemplate.execute(tx ->
                taskService.createAndDispatch(request, ctx.run().getStartedBy(), authority, evidenceRef));
        ctx.nodeRun().setTaskId(task.getId());
        markWorkflowSource(task.getId());
        ctx.nodeRun().setAgentVersions(agentVersionsJson(agentIds));

        // ⑨ 轮询 task 终态（§3.13：1000ms ±20% jitter，每轮检查取消）
        pollTaskTerminal(ctx, task.getId());

        // ⑩ 产出 output（自由 JSON 对象；未声明 outputSchema 时下游只能整体引用，§2.11）
        Object output = collectTaskOutput(task.getId());
        ctx.variables().putNodeOutput(node.getKey(), node.getOutputVar(), output);
        return NodeOutcome.SUCCEEDED;
    }

    @Override
    public boolean retryable(Throwable t) {
        if (t instanceof NodeFailedException e) {
            return "NODE_TIMEOUT".equals(e.getCode()) || "TASK_FAILED".equals(e.getCode())
                    || "AGENT_OFFLINE".equals(e.getCode()) || "AGENT_DISPATCH_FAILED".equals(e.getCode());
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** MEDIUM 动态审批的 detail（不可变快照；只存等级与模式串，不存脚本原文，C-9） */
    private String detailJson(NodeContext ctx, RiskLevel level, RiskAssessment risk) {
        Map<String, Object> detail = new java.util.LinkedHashMap<>();
        detail.put("runId", ctx.run().getId());
        detail.put("nodeKey", ctx.node().getKey());
        detail.put("nodeName", ctx.node().getName());
        detail.put("riskLevel", level.name());
        detail.put("riskMatches", risk.getBannedMatches());
        detail.put("triggeredBy", ctx.run().getStartedBy());
        detail.put("triggeredAt", java.time.LocalDateTime.now().toString());
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String inputJson(Map<String, String> values, Set<String> rawParams) {
        Map<String, Object> input = new java.util.LinkedHashMap<>();
        input.put("parameters", values);
        input.put("rawParams", new ArrayList<>(rawParams));
        try {
            return truncate(objectMapper.writeValueAsString(input), 65536);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** execToken = runId:nodeKey:attempt（§3.5 幂等③，可回溯到具体 Task/Job） */
    private String execTokenName(NodeContext ctx) {
        String token = ctx.run().getId() + ":" + ctx.node().getKey() + ":" + ctx.nodeRun().getAttempt();
        String name = ctx.node().getName() == null ? ctx.node().getKey() : ctx.node().getName();
        return truncate("[wf] " + name + " " + token, 120);
    }

    /** Task.source='workflow'（R2/G-17：前端来源筛选列；零 Job 表改动） */
    private void markWorkflowSource(String taskId) {
        try {
            taskRepository.findById(taskId).ifPresent(task -> {
                task.setSource("workflow");
                taskRepository.save(task);
            });
        } catch (Exception e) {
            log.warn("标记 task 来源失败（不影响派发）: task={} err={}", taskId, e.getMessage());
        }
    }

    /** agent 版本快照（B12：派发时从 Agent.agentVersion 读取，零 Job 表改动） */
    private String agentVersionsJson(Set<String> agentIds) {
        Map<String, String> versions = new java.util.LinkedHashMap<>();
        for (String agentId : agentIds) {
            agentRepository.findById(agentId).ifPresent(agent ->
                    versions.put(agentId, String.valueOf(agent.getAgentVersion())));
        }
        try {
            return objectMapper.writeValueAsString(versions);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * 目标解析：explicit / cluster / tag / variable 四种 mode。
     * variable 模式的 host-list 在执行前校验 agentId 存在性（§2.7 报错规范第三条）。
     */
    private Set<String> resolveTargets(NodeContext ctx, WorkflowNode node) {
        Map<String, Object> targets = node.configMap("targets");
        if (targets == null || targets.get("mode") == null) {
            throw new NodeFailedException("NODE_CONFIG_INVALID", "script-exec 节点缺少 targets.mode");
        }
        String mode = String.valueOf(targets.get("mode"));
        Set<String> agentIds = new LinkedHashSet<>();
        switch (mode) {
            case "explicit" -> {
                for (Object item : listOf(targets.get("agentIds"))) {
                    agentIds.add(String.valueOf(item));
                }
            }
            case "cluster" -> {
                List<Long> clusterIds = new ArrayList<>();
                for (Object item : listOf(targets.get("clusterIds"))) {
                    clusterIds.add(((Number) item).longValue());
                }
                agentIds.addAll(clusterService.getAgentIdsByClusterIds(clusterIds));
            }
            case "tag" -> {
                List<Long> tagIds = new ArrayList<>();
                for (Object item : listOf(targets.get("tagIds"))) {
                    tagIds.add(((Number) item).longValue());
                }
                agentIds.addAll(tagService.getAgentIdsByTagIds(tagIds));
            }
            case "variable" -> {
                String variableRef = String.valueOf(targets.get("variableRef"));
                Optional<Object> resolved = ctx.resolver().resolve(
                        com.easyshell.server.workflow.expression.ExpressionEvaluator.normalizePath(variableRef));
                if (resolved.isEmpty() || !(resolved.get() instanceof List<?> hosts) || hosts.isEmpty()) {
                    throw new NodeFailedException("WORKFLOW_TYPE_MISMATCH",
                            "targets.variableRef '" + variableRef + "' 解析结果不是非空字符串数组");
                }
                for (Object host : hosts) {
                    String agentId = String.valueOf(host);
                    if (agentRepository.findById(agentId).isEmpty()) {
                        throw new NodeFailedException("WORKFLOW_TYPE_MISMATCH",
                                "targets.variableRef 引用的 agent 不存在: " + agentId);
                    }
                    agentIds.add(agentId);
                }
            }
            default -> throw new NodeFailedException("NODE_CONFIG_INVALID", "未知 targets.mode: " + mode);
        }
        if (agentIds.isEmpty()) {
            throw new NodeFailedException("NODE_CONFIG_INVALID", "目标 agent 集合为空（mode=" + mode + "）");
        }
        return agentIds;
    }

    /** §3.13 轮询协议：1000ms ±20% jitter + 每轮检查取消 */
    private void pollTaskTerminal(NodeContext ctx, String taskId) {
        long deadline = System.currentTimeMillis()
                + ctx.node().timeoutSecOrDefault(properties.getEngine().getDefaultNodeTimeoutSec()) * 1000L;
        int baseInterval = Math.max(100, properties.getEngine().getPollIntervalMs());
        while (true) {
            if (ctx.isCancelled()) {
                throw new NodeCancelledException("运行已取消，停止等待 task " + taskId);
            }
            Optional<TaskRepository.TaskCountProjection> counts = taskRepository.findCountsById(taskId);
            if (counts.isPresent()) {
                TaskRepository.TaskCountProjection c = counts.get();
                int success = c.getSuccessCount() == null ? 0 : c.getSuccessCount();
                int failed = c.getFailedCount() == null ? 0 : c.getFailedCount();
                int total = c.getTotalCount() == null ? 0 : c.getTotalCount();
                boolean settled = total > 0 && success + failed >= total;
                if (settled) {
                    if (success == 0 && failed > 0) {
                        throw new NodeFailedException("TASK_FAILED",
                                "关联 task " + taskId + " 全部 job 失败（failed=" + failed + "/" + total + "）");
                    }
                    if (failed > 0) {
                        ctx.variables().addWarning("task " + taskId + " 部分 job 失败（failed="
                                + failed + "/" + total + "）");
                    }
                    return;
                }
            }
            if (System.currentTimeMillis() > deadline) {
                throw new NodeFailedException("NODE_TIMEOUT",
                        "等待 task " + taskId + " 终态超时");
            }
            long jittered = Math.round(baseInterval * (0.8 + 0.4 * ThreadLocalRandom.current().nextDouble()));
            try {
                Thread.sleep(jittered);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NodeCancelledException("轮询被中断");
            }
        }
    }

    /** 收集 task 的 job 输出为节点 output（可解析为 JSON 则给对象，否则给字符串） */
    private Object collectTaskOutput(String taskId) {
        List<Job> jobs = jobRepository.findByTaskId(taskId);
        StringBuilder text = new StringBuilder();
        for (Job job : jobs) {
            if (job.getOutput() == null || job.getOutput().isBlank()) {
                continue;
            }
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(job.getOutput());
        }
        String raw = sensitiveDataFilter.filter(text.toString());
        Object parsed = ExpressionEvaluator.parseJson(raw);
        return parsed != null ? parsed : raw;
    }

    /** 已审批的授权来源：拓扑祖先 approval 节点，或本节点自己的 MEDIUM 动态审批（§6.3.3） */
    private Optional<WorkflowApproval> findApprovedAuthority(NodeContext ctx) {
        Set<String> ancestors = dagValidator.ancestorMap(ctx.definition())
                .getOrDefault(ctx.node().getKey(), Set.of());
        for (WorkflowApproval approval : approvalRepository.findByRunId(ctx.run().getId())) {
            if (!"approved".equals(approval.getStatus()) || approval.getDecidedBy() == null) {
                continue;
            }
            if (approval.getNodeKey().equals(ctx.node().getKey()) || ancestors.contains(approval.getNodeKey())) {
                return Optional.of(approval);
            }
        }
        return Optional.empty();
    }

    /** 值截断到白名单边界（onUnsafeValue=truncate，仅调试用，§2.4.5） */
    private static int safePrefixLength(String value) {
        int keep = 0;
        while (keep < value.length()) {
            String prefix = value.substring(0, keep + 1);
            if (WorkflowValuePolicy.check("x", prefix, false)
                    == WorkflowValuePolicy.ValueDisposition.REJECT) {
                break;
            }
            keep++;
        }
        return keep;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return new java.util.LinkedHashMap<>();
        }
        Map<String, String> result = new java.util.LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item == null ? null : String.valueOf(item)));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            result.add(String.valueOf(item));
        }
        return result;
    }

    private static List<Object> listOf(Object value) {
        return value instanceof List<?> list ? new ArrayList<>((List<Object>) list) : List.of();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}
