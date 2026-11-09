package com.easyshell.server.workflow.service;

import com.easyshell.server.ai.model.vo.RiskAssessment;
import com.easyshell.server.ai.risk.CommandRiskEngine;
import com.easyshell.server.ai.risk.RiskLevel;
import com.easyshell.server.ai.security.SensitiveDataFilter;
import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.common.exception.WorkflowRiskBlockedException;
import com.easyshell.server.model.entity.Workflow;
import com.easyshell.server.model.entity.WorkflowRun;
import com.easyshell.server.model.vo.WorkflowRiskBlockedVO;
import com.easyshell.server.model.vo.WorkflowTestRunVO;
import com.easyshell.server.repository.WorkflowRunRepository;
import com.easyshell.server.repository.WorkflowVersionRepository;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.service.ClusterService;
import com.easyshell.server.service.TagService;
import com.easyshell.server.workflow.expression.ExpressionEvaluator;
import com.easyshell.server.workflow.expression.ExpressionException;
import com.easyshell.server.workflow.expression.ShellSafeRenderer;
import com.easyshell.server.workflow.expression.SubstitutionGuard;
import com.easyshell.server.workflow.expression.VariableResolver;
import com.easyshell.server.workflow.expression.WorkflowValuePolicy;
import com.easyshell.server.workflow.model.NodeType;
import com.easyshell.server.workflow.model.RenderResult;
import com.easyshell.server.workflow.model.WorkflowDefinition;
import com.easyshell.server.workflow.model.WorkflowNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 单节点试运行（§6.1 POST /runs/{runId}/nodes/{nodeKey}/test-run，业主选选项3 故进 M1）。
 *
 * <p><b>M1 的实现边界（对设计的一处显式降级，需在汇报中说明）</b>：test-run 是
 * <b>不派发的预演</b>——完整走 §2.4.3 的六步管线（静态检查 → 求值 → L1 → L2 → L3 → L4
 * 风险门禁），返回渲染结果与风险判定，但<b>不创建 Task/Job 行、不打扰 agent</b>。
 * 真正的"试运行并回收输出"需要 run 上下文 + attempt 语义，属 M2 的 E2 完整版。</p>
 *
 * <p>好处是安全边界完全不变：L1/L2/L3/L4 一层不少，449 的响应体同样只含等级与模式串。</p>
 */
@Slf4j
@Service
public class WorkflowTestRunService {

    private final WorkflowRunRepository runRepository;
    private final WorkflowVersionRepository versionRepository;
    private final ExpressionEvaluator expressionEvaluator;
    private final CommandRiskEngine riskEngine;
    private final SensitiveDataFilter sensitiveDataFilter;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final ClusterService clusterService;
    private final TagService tagService;

    public WorkflowTestRunService(WorkflowRunRepository runRepository,
                                  WorkflowVersionRepository versionRepository,
                                  ExpressionEvaluator expressionEvaluator,
                                  CommandRiskEngine riskEngine,
                                  SensitiveDataFilter sensitiveDataFilter,
                                  AuditLogService auditLogService,
                                  ObjectMapper objectMapper,
                                  ClusterService clusterService,
                                  TagService tagService) {
        this.runRepository = runRepository;
        this.versionRepository = versionRepository;
        this.expressionEvaluator = expressionEvaluator;
        this.riskEngine = riskEngine;
        this.sensitiveDataFilter = sensitiveDataFilter;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
        this.clusterService = clusterService;
        this.tagService = tagService;
    }

    /**
     * 试运行一个节点（run 已过 {@code requireRunAccess}/{@code requireWorkflowOwner}）。
     *
     * @throws BusinessException 400（参数/表达式/插值问题）/ 449（风险门禁）
     */
    public WorkflowTestRunVO testRun(WorkflowRun run, Workflow workflow, String nodeKey,
                                     Long userId, String username) {
        WorkflowDefinition def = loadDefinition(run.getVersionId());
        WorkflowNode node = def.nodeByKey(nodeKey);
        if (node == null) {
            throw new BusinessException(404, "WORKFLOW_NOT_FOUND: 运行定义中不存在节点 " + nodeKey);
        }
        Map<String, Object> input = parseInput(run.getInputJson());
        VariableResolver resolver = new RunInputResolver(run, workflow, input);
        List<String> warnings = new ArrayList<>();

        WorkflowTestRunVO result = switch (node.nodeType() == null ? NodeType.SCRIPT_EXEC : node.nodeType()) {
            case SCRIPT_EXEC -> previewScriptExec(node, resolver, warnings);
            case CONDITION -> previewCondition(node, resolver);
            case NOTIFY -> previewNotify(node);
            case APPROVAL, WAIT -> WorkflowTestRunVO.builder()
                    .nodeKey(nodeKey)
                    .nodeType(node.nodeType().code())
                    .status("ok")
                    .riskLevel(RiskLevel.LOW.name())
                    .riskMatches(List.of())
                    .build();
        };

        auditLogService.log(userId, username, "WORKFLOW_TEST_RUN", "workflow_run", run.getId(),
                "node=" + nodeKey + ", status=" + result.getStatus()
                        + ", risk=" + result.getRiskLevel(), null, "success");
        return result;
    }

    // ------------------------------------------------------------------
    // script-exec：§2.4.3 六步管线（不派发）
    // ------------------------------------------------------------------

    private WorkflowTestRunVO previewScriptExec(WorkflowNode node, VariableResolver resolver,
                                                List<String> warnings) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        String script = node.configString("script");
        if (script == null || script.isBlank()) {
            throw new BusinessException(400, "WORKFLOW_PARAM_INVALID: script-exec 节点缺少 script");
        }
        Map<String, String> parameters = stringMap(config.get("parameters"));
        Set<String> rawParams = Set.copyOf(stringList(config.get("rawParams")));
        String riskPolicy = node.configString("riskPolicy", "block");

        // ① 静态检查
        for (String loose : ShellSafeRenderer.looseSlots(script)) {
            if (!ShellSafeRenderer.isWellFormedSlot(loose)) {
                throw new BusinessException(400, "PARAM_SLOT_MALFORMED: 占位符 " + loose
                        + " 不合规：NAME 必须匹配 ^[A-Z][A-Z0-9_]*$");
            }
        }
        for (String slot : ShellSafeRenderer.slotNames(script)) {
            if (!parameters.containsKey(slot)) {
                throw new BusinessException(400, "PARAM_MISSING: 缺少参数 " + slot + " 的取值");
            }
        }
        for (String rawSlot : ShellSafeRenderer.rawSlotNames(script)) {
            if (!rawParams.contains(rawSlot)) {
                throw new BusinessException(400, "PARAM_RAW_UNDECLARED: 占位符 {{" + rawSlot
                        + ":raw}} 未在 rawParams 中声明");
            }
        }

        // ② 参数表达式求值
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            try {
                values.put(entry.getKey(), expressionEvaluator.resolveTemplateValue(entry.getValue(),
                        resolver, warnings::add));
            } catch (ExpressionException e) {
                throw new BusinessException(400, e.getCode() + ": 参数 " + entry.getKey()
                        + " 求值失败: " + e.getMessage());
            }
        }

        // ③ L1 值策略
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (WorkflowValuePolicy.check(entry.getKey(), entry.getValue(), rawParams.contains(entry.getKey()))
                    == WorkflowValuePolicy.ValueDisposition.REJECT) {
                throw new BusinessException(400, "PARAM_UNSAFE: 参数 " + entry.getKey()
                        + " 的值越出安全字符集（位置 "
                        + WorkflowValuePolicy.firstOffendingChar(entry.getValue()) + "）");
            }
        }

        // ④ L2 渲染 + ⑤ L3 segment 级禁替换
        RenderResult rendered = ShellSafeRenderer.render(script, values, rawParams);
        String reject = SubstitutionGuard.rejectSubstitution(rendered.rendered(), rendered.valueRanges());
        if (reject != null) {
            throw new BusinessException(400, reject + ": 参数值渲染结果被 L3 拒绝");
        }

        // ⑥ L4 风险门禁（与 ScriptExecNodeExecutor 同口径：BANNED/HIGH 拒，MEDIUM 看 riskPolicy）
        RiskAssessment risk = riskEngine.assessScript(rendered.rendered());
        RiskLevel level = risk.getOverallRisk();
        List<String> matches = risk.getBannedMatches() == null ? List.of() : risk.getBannedMatches();
        if (level == RiskLevel.BANNED) {
            throw riskBlocked("WORKFLOW_RISK_BLOCKED", "脚本命中封禁模式（仅人工审批可豁免）",
                    level, matches, riskPolicy, node.getKey());
        }
        if (level == RiskLevel.HIGH) {
            throw riskBlocked("RISK_NEED_APPROVAL", "高风险脚本需要本 run 内已通过的 approval 祖先节点授权（§6.3.3）",
                    level, matches, riskPolicy, node.getKey());
        }
        if (level == RiskLevel.MEDIUM && !"allow_medium".equals(riskPolicy)) {
            throw riskBlocked("RISK_NEED_APPROVAL", "中风险脚本被 riskPolicy=" + riskPolicy
                            + " 拒绝；如需执行请改为 allow_medium（转人工审批）或降低脚本风险",
                    level, matches, riskPolicy, node.getKey());
        }

        return WorkflowTestRunVO.builder()
                .nodeKey(node.getKey())
                .nodeType(NodeType.SCRIPT_EXEC.code())
                .status("ok")
                .riskLevel(level.name())
                .riskMatches(matches)
                .riskPolicy(riskPolicy)
                .renderedScript(rendered.rendered())
                .resolvedParameters(values)
                .targetAgentCount(countTargets(node))
                .warnings(filterWarnings(warnings))
                .build();
    }

    /** 出站统一过 SensitiveDataFilter（§6.7.1：掩码 ***，禁止第二套掩码实现） */
    private List<String> filterWarnings(List<String> warnings) {
        if (warnings == null || warnings.isEmpty()) {
            return List.of();
        }
        return warnings.stream().map(sensitiveDataFilter::filter).toList();
    }

    private WorkflowTestRunVO previewCondition(WorkflowNode node, VariableResolver resolver) {
        String expression = node.configString("expression");
        if (expression == null || expression.isBlank()) {
            throw new BusinessException(400, "WORKFLOW_PARAM_INVALID: condition 节点缺少 expression");
        }
        boolean value;
        try {
            value = expressionEvaluator.evaluate(expression, resolver);
        } catch (ExpressionException e) {
            throw new BusinessException(400, e.getCode() + ": 条件求值失败: " + e.getMessage());
        }
        return WorkflowTestRunVO.builder()
                .nodeKey(node.getKey())
                .nodeType(NodeType.CONDITION.code())
                .status("ok")
                .riskLevel(RiskLevel.LOW.name())
                .riskMatches(List.of())
                .conditionResult(value)
                .warnings(List.of())
                .build();
    }

    private WorkflowTestRunVO previewNotify(WorkflowNode node) {
        return WorkflowTestRunVO.builder()
                .nodeKey(node.getKey())
                .nodeType(NodeType.NOTIFY.code())
                .status("ok")
                .riskLevel(RiskLevel.LOW.name())
                .riskMatches(List.of())
                .warnings(List.of("M1 试运行不外发通知，仅校验配置"))
                .build();
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 449 风险门禁：响应体<b>只含等级与模式串</b>（B2 #13 / C-9）。
     * 刻意不引用 {@link RiskAssessment}——它携带 {@code CommandRisk.command} 原文。
     */
    private WorkflowRiskBlockedException riskBlocked(String code, String message, RiskLevel level,
                                                     List<String> matches, String riskPolicy,
                                                     String nodeKey) {
        return new WorkflowRiskBlockedException(WorkflowRiskBlockedVO.builder()
                .code(code)
                .message(message)
                .riskLevel(level.name())
                .riskMatches(matches)
                .riskPolicy(riskPolicy)
                .nodeKey(nodeKey)
                .build());
    }

    /** 目标 agent 数（试运行只报数，不校验 agent 在线性——真校验在派发前） */
    private Integer countTargets(WorkflowNode node) {
        Map<String, Object> targets = node.configMap("targets");
        if (targets == null || targets.get("mode") == null) {
            return null;
        }
        String mode = String.valueOf(targets.get("mode"));
        try {
            return switch (mode) {
                case "explicit" -> {
                    List<String> ids = stringList(targets.get("agentIds"));
                    yield ids.size();
                }
                case "tag" -> tagService.getAgentIdsByTagIds(
                        longList(targets.get("tagIds"))).size();
                case "cluster" -> clusterService.getAgentIdsByClusterIds(
                        longList(targets.get("clusterIds"))).size();
                default -> null;
            };
        } catch (Exception e) {
            log.warn("试运行目标解析失败（按未知处理）: mode={} err={}", mode, e.getMessage());
            return null;
        }
    }

    private WorkflowDefinition loadDefinition(Long versionId) {
        String json = versionRepository.findDefinition(versionId)
                .orElseThrow(() -> new BusinessException(404, "WORKFLOW_NOT_FOUND: 版本定义不存在"));
        try {
            return objectMapper.readValue(json, WorkflowDefinition.class);
        } catch (Exception e) {
            throw new BusinessException(500, "WORKFLOW_INTERNAL: 定义解析失败");
        }
    }

    private Map<String, Object> parseInput(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** run.input.* 与 run 元信息的只读解析器（nodes.* 一律不存在——试运行没有上游执行） */
    private record RunInputResolver(WorkflowRun run, Workflow workflow, Map<String, Object> input)
            implements VariableResolver {

        @Override
        public Optional<Object> resolve(String path) {
            String normalized = com.easyshell.server.workflow.expression.ExpressionEvaluator
                    .normalizePath(path);
            String[] segments = normalized.split("\\.");
            if ("run".equals(segments[0])) {
                if (segments.length >= 3 && "input".equals(segments[1])) {
                    return Optional.ofNullable(input.get(segments[2]));
                }
                if (segments.length == 2) {
                    return switch (segments[1]) {
                        case "id" -> Optional.of(run.getId());
                        case "workflowName" -> Optional.of(workflow.getName());
                        case "startedBy" -> Optional.of(String.valueOf(run.getStartedBy()));
                        case "triggerType" -> Optional.of(run.getTriggerType());
                        default -> Optional.empty();
                    };
                }
            }
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> stringMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, String> result = new LinkedHashMap<>();
            map.forEach((k, v) -> result.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
            return result;
        }
        return Map.of();
    }

    private static List<Long> longList(Object value) {
        if (value instanceof List<?> list) {
            List<Long> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Number n) {
                    result.add(n.longValue());
                } else if (item instanceof String s && !s.isBlank()) {
                    try {
                        result.add(Long.valueOf(s.trim()));
                    } catch (NumberFormatException ignored) {
                        // 非法 id 交由服务层校验，这里只计数
                    }
                }
            }
            return result;
        }
        return List.of();
    }

    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
            return result;
        }
        return List.of();
    }
}
