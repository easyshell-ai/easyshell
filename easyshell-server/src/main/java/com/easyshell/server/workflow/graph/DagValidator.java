package com.easyshell.server.workflow.graph;

import com.easyshell.server.workflow.expression.ExpressionEvaluator;
import com.easyshell.server.workflow.expression.ExpressionImportGuard;
import com.easyshell.server.workflow.model.FailurePolicy;
import com.easyshell.server.workflow.model.NodeType;
import com.easyshell.server.workflow.model.ValueType;
import com.easyshell.server.workflow.model.VariableSpec;
import com.easyshell.server.workflow.model.WorkflowDefinition;
import com.easyshell.server.workflow.model.WorkflowEdge;
import com.easyshell.server.workflow.model.WorkflowNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 工作流图校验器（结构合法性，发布门禁的核心）。
 *
 * <p>算法来源：{@code ai/orchestrator/DagExecutor.hasCycle} 的 Kahn 拓扑排序，
 * <b>但已修 SF-1</b>——旧实现 {@code inDegree.merge} 计数而 {@code adj} 用 {@code Set}
 * 去重，{@code dependsOn=[0,0]} 时误报有环（复核实测证实）。本实现用
 * {@code Map<String, Set<String>>} 存去重后的邻接表 + 入度数组，重复依赖天然无歧义，
 * 并用 DFS 三色标记给出具体环路径（错误信息要能指认环上的节点）。</p>
 *
 * <p>覆盖规则（ERROR 除注明外）：环、孤立节点（WARNING）、缺失入口/出口、重复 ID、
 * 节点类型未知、连线端点不存在、自连/重复连线、类型不兼容的连线（把手契约：
 * 非 condition 节点 1 出、condition 最多 2 出）、nodeKey/outputVar 格式与冲突、
 * 同一 outputVar 多写者、表达式静态分析、表达式引用非祖先节点、变量类型未知。</p>
 */
@Slf4j
@Component
public class DagValidator {

    /** nodeKey / outputVar 格式（§2.2） */
    public static final Pattern NODE_KEY = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");

    /** 模板里的 ${path} 引用（只用于静态祖先校验，不改变 ExpressionEvaluator 的渲染语义） */
    private static final Pattern TEMPLATE_REF = Pattern.compile("\\$\\{([^{}]*)}");

    /** AI input 的纯路径形态（不含 ${} 时按一条路径读取） */
    private static final Pattern PURE_PATH = Pattern.compile(
            "^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$");

    /** wait 节点最长等待秒（防一个节点占死引擎线程） */
    private static final int MAX_WAIT_SECONDS = 3600;

    private final ExpressionEvaluator expressionEvaluator;

    public DagValidator(ExpressionEvaluator expressionEvaluator) {
        this.expressionEvaluator = expressionEvaluator;
    }

    // ==================================================================
    // 环检测（Kahn + DFS 三色）
    // ==================================================================

    /**
     * 图是否有环（幂等、纯函数，可反复调用）。
     *
     * <p>与 {@code DagExecutor.hasCycle} 的差异：邻接表用 Set 去重后入度按去重边计数，
     * 重复依赖不再误报；节点缺失按"不存在的节点"忽略而不是抛 NPE。</p>
     */
    public boolean hasCycle(WorkflowDefinition def) {
        return !findCyclePath(def).isEmpty();
    }

    /**
     * 找出一个环的节点路径（空 list = 无环）。用于错误信息指认环上节点。
     */
    public List<String> findCyclePath(WorkflowDefinition def) {
        if (def == null || def.getNodes().isEmpty()) {
            return List.of();
        }
        Map<String, Set<String>> adj = adjacency(def);
        Map<String, Integer> color = new HashMap<>();   // 0=白 1=灰 2=黑
        for (WorkflowNode node : def.getNodes()) {
            color.put(node.getKey(), 0);
        }
        List<String> path = new ArrayList<>();
        for (WorkflowNode node : def.getNodes()) {
            if (color.get(node.getKey()) == 0 && dfsCycle(node.getKey(), adj, color, path)) {
                return path;
            }
        }
        return List.of();
    }

    private boolean dfsCycle(String current, Map<String, Set<String>> adj,
                             Map<String, Integer> color, List<String> path) {
        color.put(current, 1);
        path.add(current);
        for (String next : adj.getOrDefault(current, Set.of())) {
            if (!color.containsKey(next)) {
                continue;   // 端点不存在的边由结构校验负责，这里不当作环
            }
            if (color.get(next) == 1) {
                // 找到环：从 next 在 path 中首次出现处截断
                int from = path.indexOf(next);
                path.subList(0, from).clear();
                return true;
            }
            if (color.get(next) == 0 && dfsCycle(next, adj, color, path)) {
                return true;
            }
        }
        color.put(current, 2);
        path.remove(path.size() - 1);
        return false;
    }

    /** 去重邻接表（from -> to 集合） */
    private Map<String, Set<String>> adjacency(WorkflowDefinition def) {
        Map<String, Set<String>> adj = new HashMap<>();
        for (WorkflowNode node : def.getNodes()) {
            adj.putIfAbsent(node.getKey(), new LinkedHashSet<>());
        }
        for (WorkflowEdge edge : def.getEdges()) {
            if (edge.getFrom() == null || edge.getTo() == null) {
                continue;
            }
            adj.computeIfAbsent(edge.getFrom(), k -> new LinkedHashSet<>()).add(edge.getTo());
        }
        return adj;
    }

    // ==================================================================
    // 全量结构校验
    // ==================================================================

    public List<ValidationIssue> validate(WorkflowDefinition def) {
        ValidationIssue.Sink sink = new ValidationIssue.Sink();
        validate(def, sink);
        return sink.issues();
    }

    public void validate(WorkflowDefinition def, ValidationIssue.Sink sink) {
        if (def == null) {
            sink.error("DEFINITION_EMPTY", "工作流定义为空", null);
            return;
        }
        if (def.getNodes() == null || def.getNodes().isEmpty()) {
            sink.error("GRAPH_EMPTY", "工作流没有任何节点", null);
            return;
        }

        // ① 节点：重复 key / key 格式 / 类型未知
        Set<String> keys = new HashSet<>();
        Set<String> duplicateKeys = new LinkedHashSet<>();
        for (WorkflowNode node : def.getNodes()) {
            if (node.getKey() == null || !NODE_KEY.matcher(node.getKey()).matches()) {
                sink.error("NODE_KEY_MALFORMED",
                        "nodeKey '" + node.getKey() + "' 不合规：必须匹配 ^[a-z][a-z0-9_]{0,63}$", node.getKey());
                continue;
            }
            if (!keys.add(node.getKey())) {
                duplicateKeys.add(node.getKey());
            }
            if (node.nodeType() == null) {
                sink.error("NODE_TYPE_UNKNOWN",
                        "节点 '" + node.getKey() + "' 类型未知: " + node.getType(), node.getKey());
            }
        }
        for (String key : duplicateKeys) {
            sink.error("NODE_KEY_DUPLICATE", "nodeKey 重复: " + key, key);
        }

        // ② outputVar：格式、与 nodeKey 冲突、多写者
        Map<String, String> outputVarWriters = new HashMap<>();
        for (WorkflowNode node : def.getNodes()) {
            String outputVar = node.getOutputVar();
            if (outputVar == null || outputVar.isBlank()) {
                continue;
            }
            if (!NODE_KEY.matcher(outputVar).matches()) {
                sink.error("OUTPUT_VAR_MALFORMED",
                        "outputVar '" + outputVar + "' 不合规：必须匹配 ^[a-z][a-z0-9_]{0,63}$", node.getKey());
            }
            if (outputVar.equals(node.getKey())) {
                sink.error("NODE_KEY_OUTPUT_VAR_CONFLICT",
                        "nodeKey 与 outputVar 不得相同: " + outputVar, node.getKey());
            }
            for (WorkflowNode other : def.getNodes()) {
                if (!other.getKey().equals(node.getKey()) && outputVar.equals(other.getKey())) {
                    sink.error("NODE_KEY_OUTPUT_VAR_CONFLICT",
                            "outputVar '" + outputVar + "' 与节点 '" + other.getKey() + "' 的 nodeKey 冲突", node.getKey());
                }
            }
            String previous = outputVarWriters.put(outputVar, node.getKey());
            if (previous != null) {
                sink.error("OUTPUT_VAR_MULTI_WRITER",
                        "outputVar '" + outputVar + "' 有多个写者: " + previous + " 与 " + node.getKey()
                                + "（M1 限制单写者，fan-in 合并在 M2）", node.getKey());
            }
        }

        // ③ 连线：端点存在 / 自连 / 重复 / 把手契约（类型不兼容）
        Set<String> edgeIds = new HashSet<>();
        Set<String> edgePairs = new HashSet<>();
        Map<String, List<WorkflowEdge>> outEdges = new HashMap<>();
        for (WorkflowEdge edge : def.getEdges()) {
            if (edge.getId() != null && !edgeIds.add(edge.getId())) {
                sink.error("EDGE_ID_DUPLICATE", "连线 id 重复: " + edge.getId(), null);
            }
            if (edge.getFrom() == null || !def.hasNode(edge.getFrom())) {
                sink.error("EDGE_ENDPOINT_MISSING",
                        "连线 '" + edge.getId() + "' 的起点不存在: " + edge.getFrom(), null);
                continue;
            }
            if (edge.getTo() == null || !def.hasNode(edge.getTo())) {
                sink.error("EDGE_ENDPOINT_MISSING",
                        "连线 '" + edge.getId() + "' 的终点不存在: " + edge.getTo(), null);
                continue;
            }
            if (edge.getFrom().equals(edge.getTo())) {
                sink.error("EDGE_SELF_LOOP", "连线不允许自连: " + edge.getFrom(), edge.getFrom());
                continue;
            }
            if (!edgePairs.add(edge.getFrom() + "->" + edge.getTo())) {
                sink.error("EDGE_DUPLICATE",
                        "重复连线: " + edge.getFrom() + " -> " + edge.getTo(), edge.getTo());
                continue;
            }
            outEdges.computeIfAbsent(edge.getFrom(), k -> new ArrayList<>()).add(edge);
        }
        for (WorkflowNode node : def.getNodes()) {
            List<WorkflowEdge> outs = outEdges.getOrDefault(node.getKey(), List.of());
            NodeType type = node.nodeType();
            if (type == null) {
                continue;
            }
            if (type == NodeType.CONDITION) {
                if (outs.size() > 2) {
                    sink.error("EDGE_TYPE_INCOMPATIBLE",
                            "condition 节点最多 2 条出边（true/false handle），实际 " + outs.size(), node.getKey());
                }
                int trueHandles = 0;
                int falseHandles = 0;
                for (WorkflowEdge out : outs) {
                    String handle = out.getSourceHandle();
                    if (handle == null || handle.isBlank()) {
                        continue;
                    }
                    if ("true".equals(handle)) {
                        trueHandles++;
                    } else if ("false".equals(handle)) {
                        falseHandles++;
                    } else {
                        sink.error("EDGE_TYPE_INCOMPATIBLE",
                                "condition 出边 sourceHandle 只能是 true / false，实际 '" + handle
                                        + "'（边 " + out.getFrom() + " -> " + out.getTo() + "）", node.getKey());
                    }
                }
                if (trueHandles > 1) {
                    sink.error("EDGE_TYPE_INCOMPATIBLE",
                            "condition 节点 true handle 最多一条出边，实际 " + trueHandles, node.getKey());
                }
                if (falseHandles > 1) {
                    sink.error("EDGE_TYPE_INCOMPATIBLE",
                            "condition 节点 false handle 最多一条出边，实际 " + falseHandles, node.getKey());
                }
                long withCondition = outs.stream()
                        .filter(e -> e.getCondition() != null && !e.getCondition().isBlank()).count();
                boolean allHandlesPresent = outs.stream()
                        .allMatch(e -> e.getSourceHandle() != null && !e.getSourceHandle().isBlank());
                // 旧语义只允许一条出边带 condition；新定义全部用 sourceHandle 时，
                // condition 字段允许保留（被 handle 门控取代），不再按旧规则报错
                if (withCondition > 1 && !allHandlesPresent) {
                    sink.error("EDGE_TYPE_INCOMPATIBLE",
                            "condition 节点只允许一条出边带 condition（旧定义 true handle 语义），实际 "
                                    + withCondition + "；新定义请改用 sourceHandle=true/false", node.getKey());
                }
            } else if (type == NodeType.END) {
                if (!outs.isEmpty()) {
                    sink.error("GRAPH_END_INVALID",
                            "end 节点不允许有出边，实际 " + outs.size() + " 条", node.getKey());
                }
            } else if (outs.size() > 1) {
                sink.error("EDGE_TYPE_INCOMPATIBLE",
                        type.code() + " 节点是 1 入 1 出，不允许 " + outs.size() + " 条出边", node.getKey());
            }
        }

        // ③-b start / end 拓扑（工作流 2.0 §2.1）
        List<WorkflowNode> startNodes = def.getNodes().stream()
                .filter(n -> n.nodeType() == NodeType.START).toList();
        List<WorkflowNode> endNodes = def.getNodes().stream()
                .filter(n -> n.nodeType() == NodeType.END).toList();
        if (startNodes.size() > 1) {
            sink.error("GRAPH_START_INVALID",
                    "工作流最多 1 个 start 节点，实际 " + startNodes.size(), startNodes.get(0).getKey());
        }
        for (WorkflowNode start : startNodes) {
            if (!def.inEdges(start.getKey()).isEmpty()) {
                sink.error("GRAPH_START_INVALID",
                        "start 节点不允许有入边，实际 " + def.inEdges(start.getKey()).size() + " 条",
                        start.getKey());
            }
        }
        if (endNodes.size() > 1) {
            sink.error("GRAPH_END_INVALID",
                    "工作流最多 1 个 end 节点，实际 " + endNodes.size(), endNodes.get(0).getKey());
        }
        for (WorkflowNode end : endNodes) {
            if (def.getNodes().size() > 1 && def.inEdges(end.getKey()).isEmpty()) {
                sink.error("GRAPH_END_INVALID",
                        "end 节点必须有入边（定义里还有其他节点）", end.getKey());
            }
        }
        // 有 end 时其他 sink 只警告（旧工作流没有 end，不能因为补端提示阻断发布）
        if (!endNodes.isEmpty()) {
            for (WorkflowNode node : def.getNodes()) {
                if (node.nodeType() == NodeType.END) {
                    continue;
                }
                if (outEdges.getOrDefault(node.getKey(), List.of()).isEmpty()) {
                    sink.warning("GRAPH_SINK_NOT_END",
                            "节点 '" + node.getKey() + "' 是汇点但不是 end 节点；运行输出可能不完整",
                            node.getKey());
                }
            }
        }

        // ④ 环 / 入口 / 出口 / 孤立节点
        // 有环时环错误优先报告，但入口/出口判定仍然独立成立（有环图也可能有入口），
        // 因此两项检查不放在 else 分支里——错误信息要能一次说完全部结构问题
        List<String> cycle = findCyclePath(def);
        if (!cycle.isEmpty()) {
            sink.error("GRAPH_CYCLE", "工作流图存在环: " + String.join(" -> ", cycle), cycle.get(0));
        }
        Map<String, Integer> inDegree = inDegrees(def);
        boolean hasEntry = def.getNodes().stream().anyMatch(n -> inDegree.getOrDefault(n.getKey(), 0) == 0);
        if (!hasEntry) {
            sink.error("GRAPH_NO_ENTRY", "没有入口节点（所有节点都有入边）", null);
        }
        boolean hasExit = def.getNodes().stream()
                .anyMatch(n -> outEdges.getOrDefault(n.getKey(), List.of()).isEmpty());
        if (!hasExit) {
            sink.error("GRAPH_NO_EXIT", "没有出口节点（所有节点都有出边）", null);
        }
        for (WorkflowNode node : def.getNodes()) {
            boolean isolated = outEdges.getOrDefault(node.getKey(), List.of()).isEmpty()
                    && def.inEdges(node.getKey()).isEmpty();
            if (isolated && def.getNodes().size() > 1) {
                sink.warning("NODE_ISOLATED", "孤立节点（无入边也无出边）: " + node.getKey(), node.getKey());
            }
        }

        // ⑤ 变量规格
        if (def.getVariables() != null) {
            for (Map.Entry<String, VariableSpec> entry : def.getVariables().entrySet()) {
                VariableSpec spec = entry.getValue();
                if (spec == null || ValueType.fromSpec(spec.getType()) == null) {
                    sink.error("VARIABLE_TYPE_UNKNOWN",
                            "变量 '" + entry.getKey() + "' 类型未知: " + (spec == null ? null : spec.getType()), null);
                }
            }
        }

        // ⑥ 表达式：语法 / 禁用 token / 引用非祖先节点（含 transform/end 模板与 AI input）
        Map<String, Set<String>> ancestors = ancestorMap(def);
        Map<String, String> aliases = outputVarWriters;
        for (WorkflowNode node : def.getNodes()) {
            List<String> expressions = new ArrayList<>();
            if (node.nodeType() == NodeType.CONDITION) {
                expressions.add(node.configString("expression"));
            }
            for (WorkflowEdge edge : def.getEdges()) {
                if (node.getKey().equals(edge.getFrom()) && edge.getCondition() != null
                        && !edge.getCondition().isBlank()) {
                    expressions.add(edge.getCondition());
                }
            }
            for (String expression : expressions) {
                if (expression == null) {
                    continue;
                }
                List<String> errors = expressionEvaluator.staticAnalyze(expression);
                for (String error : errors) {
                    sink.error("EXPRESSION_SYNTAX", error + "（节点 " + node.getKey() + "）", node.getKey());
                }
                if (!errors.isEmpty()) {
                    continue;
                }
                Set<String> allowed = ancestors.getOrDefault(node.getKey(), Set.of());
                for (String referenced : expressionEvaluator.referencedNodeKeys(expression, aliases)) {
                    if (!allowed.contains(referenced)) {
                        sink.error("EXPRESSION_REFERENCE_NOT_ANCESTOR",
                                "表达式引用了非祖先节点 '" + referenced + "' 的输出（节点 " + node.getKey()
                                        + "）：节点只能读其拓扑前驱的输出", node.getKey());
                    }
                }
            }

            // 模板字段（${path} 插值 / AI input 纯路径）：只做引用祖先校验，不做表达式词法分析
            Set<String> allowed = ancestors.getOrDefault(node.getKey(), Set.of());
            for (String template : templateFields(node)) {
                for (String referenced : templateReferencedNodeKeys(template, aliases)) {
                    if (!allowed.contains(referenced)) {
                        sink.error("EXPRESSION_REFERENCE_NOT_ANCESTOR",
                                "模板引用了非祖先节点 '" + referenced + "' 的输出（节点 " + node.getKey()
                                        + "）：节点只能读其拓扑前驱的输出", node.getKey());
                    }
                }
            }
            validateAiRawParams(node, def, aliases, sink);
            // 失败策略取值合法性（未知值在运行期按最严格 ABORT 处理，发布期先警告）
            for (String policy : new String[]{node.getOnFailure()}) {
                if (policy != null && !policy.isBlank() && FailurePolicy.fromCode(policy) == null) {
                    sink.warning("FAILURE_POLICY_UNKNOWN",
                            "onFailure '" + policy + "' 无法识别，运行期按 abort 处理", node.getKey());
                }
            }
        }

        // ⑦ 各节点类型执行器补充校验（脚本槽位、wait 时长、approval 配置等）
        for (WorkflowNode node : def.getNodes()) {
            if (node.nodeType() == NodeType.WAIT) {
                int duration = node.configInt("durationSec", 0);
                if (duration < 1 || duration > MAX_WAIT_SECONDS) {
                    sink.error("NODE_CONFIG_INVALID",
                            "wait 节点 durationSec 必须在 1.." + MAX_WAIT_SECONDS + " 之间，实际 " + duration,
                            node.getKey());
                }
            }
        }
    }

    /** 需要做 ${path} 引用校验的模板字段（全部模板型 config，不再只有 transform/end） */
    private List<String> templateFields(WorkflowNode node) {
        if (node.nodeType() == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        switch (node.nodeType()) {
            case SCRIPT_EXEC -> addMapValues(node, "parameters", result);
            case TRANSFORM -> addMappingValues(node, "mappings", result);
            case END -> addMappingValues(node, "outputs", result);
            case AI_ANALYZE -> addString(node, "input", result);
            case NOTIFY -> {
                addString(node, "messageTemplate", result);
                addMapValues(node, "parameters", result);
            }
            case APPROVAL -> {
                addString(node, "title", result);
                addString(node, "detailTemplate", result);
                addMapValues(node, "parameters", result);
            }
            default -> {
            }
        }
        return result;
    }

    private void addString(WorkflowNode node, String configKey, List<String> result) {
        Object value = node.getConfig() == null ? null : node.getConfig().get(configKey);
        if (value instanceof String text) {
            result.add(text);
        }
    }

    private void addMapValues(WorkflowNode node, String configKey, List<String> result) {
        Map<String, Object> map = node.configMap(configKey);
        if (map == null) {
            return;
        }
        for (Object value : map.values()) {
            if (value instanceof String text) {
                result.add(text);
            }
        }
    }

    private void addMappingValues(WorkflowNode node, String configKey, List<String> result) {
        List<Object> rows = node.configList(configKey);
        if (rows == null) {
            return;
        }
        for (Object row : rows) {
            if (row instanceof Map<?, ?> map && map.get("value") instanceof String value) {
                result.add(value);
            }
        }
    }

    /**
     * rawParams 的值绕过 L1 字符集与 L3 段级禁替换检查，直接进入脚本。AI 节点输出可被
     * 上游提示注入污染，若被 raw 槽位直引，提示注入就能变成命令执行面——发布期直接拒绝。
     */
    private void validateAiRawParams(WorkflowNode node, WorkflowDefinition def,
                                     Map<String, String> aliases, ValidationIssue.Sink sink) {
        if (node.nodeType() != NodeType.SCRIPT_EXEC) {
            return;
        }
        List<Object> rawSlots = node.configList("rawParams");
        Map<String, Object> parameters = node.configMap("parameters");
        if (rawSlots == null || rawSlots.isEmpty() || parameters == null) {
            return;
        }
        Set<String> aiNodes = new HashSet<>();
        for (WorkflowNode candidate : def.getNodes()) {
            if (candidate.nodeType() == NodeType.AI_ANALYZE) {
                aiNodes.add(candidate.getKey());
            }
        }
        if (aiNodes.isEmpty()) {
            return;
        }
        for (Object rawSlot : rawSlots) {
            if (!(rawSlot instanceof String slot)) {
                continue;
            }
            Object value = parameters.get(slot);
            if (!(value instanceof String template)) {
                continue;
            }
            for (String referenced : templateReferencedNodeKeys(template, aliases)) {
                if (aiNodes.contains(referenced)) {
                    sink.error("AI_OUTPUT_RAW_FORBIDDEN",
                            "脚本参数 '" + slot + "' 在 rawParams 中直接引用 ai-analyze 节点 '"
                                    + referenced + "' 的输出；raw 值绕过值策略，禁止进入命令面",
                            node.getKey());
                }
            }
        }
    }

    /**
     * 从模板文本中提取被引用的 nodeKey：
     * <ul>
     *   <li>{@code ${nodes.check.output.x}} → check</li>
     *   <li>{@code ${alias.x}} → alias 的写者节点</li>
     *   <li>AI input 是纯路径（不含 ${}）时，整串按一条路径处理</li>
     * </ul>
     * 未知别名/非 nodes 路径（run.input.* 等）不产生引用，运行期由 resolver fail-closed。
     */
    private Set<String> templateReferencedNodeKeys(String text, Map<String, String> aliases) {
        Set<String> keys = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return keys;
        }
        String trimmed = text.trim();
        if (!trimmed.contains("${")) {
            if (PURE_PATH.matcher(trimmed).matches()) {
                collectNodeKey(trimmed, aliases, keys);
            }
            return keys;
        }
        java.util.regex.Matcher matcher = TEMPLATE_REF.matcher(trimmed);
        while (matcher.find()) {
            collectNodeKey(matcher.group(1).trim(), aliases, keys);
        }
        return keys;
    }

    private void collectNodeKey(String rawPath, Map<String, String> aliases, Set<String> keys) {
        String normalized = ExpressionEvaluator.normalizePath(rawPath);
        String[] segments = normalized.split("\\.");
        if (segments.length >= 2 && "nodes".equals(segments[0])) {
            keys.add(segments[1]);
            return;
        }
        if (segments.length >= 1) {
            String writer = aliases.get(segments[0]);
            if (writer != null) {
                keys.add(writer);
            }
        }
    }

    /** 每个节点的入度（重复边按一条计） */
    private Map<String, Integer> inDegrees(WorkflowDefinition def) {
        Map<String, Integer> inDegree = new HashMap<>();
        for (WorkflowNode node : def.getNodes()) {
            inDegree.putIfAbsent(node.getKey(), 0);
        }
        Set<String> seen = new HashSet<>();
        for (WorkflowEdge edge : def.getEdges()) {
            if (edge.getFrom() == null || edge.getTo() == null) {
                continue;
            }
            if (seen.add(edge.getFrom() + "->" + edge.getTo())) {
                inDegree.merge(edge.getTo(), 1, Integer::sum);
            }
        }
        return inDegree;
    }

    /**
     * 每个节点的拓扑祖先集合（含传递前驱；不含自身）。
     * 用于发布期 ERROR⑦"引用集合 ⊆ 祖先集合"与运行期授权判定（approval 祖先）。
     */
    public Map<String, Set<String>> ancestorMap(WorkflowDefinition def) {
        Map<String, Set<String>> result = new HashMap<>();
        if (def == null) {
            return result;
        }
        Map<String, List<String>> directPredecessors = new HashMap<>();
        for (WorkflowNode node : def.getNodes()) {
            directPredecessors.put(node.getKey(), new ArrayList<>());
        }
        Set<String> seen = new HashSet<>();
        for (WorkflowEdge edge : def.getEdges()) {
            if (edge.getFrom() == null || edge.getTo() == null) {
                continue;
            }
            if (seen.add(edge.getFrom() + "->" + edge.getTo())) {
                directPredecessors.computeIfAbsent(edge.getTo(), k -> new ArrayList<>()).add(edge.getFrom());
            }
        }
        for (WorkflowNode node : def.getNodes()) {
            result.put(node.getKey(), collectAncestors(node.getKey(), directPredecessors, new HashSet<>()));
        }
        return result;
    }

    private Set<String> collectAncestors(String key, Map<String, List<String>> directPredecessors,
                                         Set<String> visited) {
        Set<String> ancestors = new LinkedHashSet<>();
        Deque<String> stack = new ArrayDeque<>(directPredecessors.getOrDefault(key, List.of()));
        while (!stack.isEmpty()) {
            String current = stack.pop();
            if (!visited.add(current)) {
                continue;
            }
            ancestors.add(current);
            stack.addAll(directPredecessors.getOrDefault(current, List.of()));
        }
        return ancestors;
    }

    /** 便捷方法：nodeKey 的拓扑祖先集合（含自身则为 true） */
    public boolean isAncestor(WorkflowDefinition def, String ancestorKey, String nodeKey) {
        return ancestorMap(def).getOrDefault(nodeKey, Set.of()).contains(ancestorKey);
    }
}
