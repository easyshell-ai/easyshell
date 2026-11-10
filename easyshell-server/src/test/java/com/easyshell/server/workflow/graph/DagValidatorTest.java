package com.easyshell.server.workflow.graph;

import com.easyshell.server.workflow.model.WorkflowDefinition;
import com.easyshell.server.workflow.model.WorkflowEdge;
import com.easyshell.server.workflow.model.WorkflowNode;
import com.easyshell.server.workflow.expression.ExpressionEvaluator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DagValidator 单测（设计 §8.1 S4：环 + 可达性 + 孤立 + nodeKey 唯一 + 重复依赖去重）。
 *
 * <p>覆盖：环检测（Kahn + DFS 三色，重复依赖不再误报）、缺失入口/出口、孤立节点、
 * 重复 ID、节点类型未知、连线端点不存在、类型不兼容的连线（把手契约）、
 * nodeKey/outputVar 格式与冲突、表达式静态分析、引用非祖先节点。</p>
 */
@DisplayName("DagValidator — 工作流图结构校验")
class DagValidatorTest {

    private DagValidator validator;

    @BeforeEach
    void setUp() {
        validator = new DagValidator(new ExpressionEvaluator(new ObjectMapper()));
    }

    private WorkflowNode node(String key, String type) {
        WorkflowNode node = new WorkflowNode();
        node.setKey(key);
        node.setType(type);
        node.setName(key);
        node.setConfig(new LinkedHashMap<>(Map.of("script", "echo hi")));
        return node;
    }

    private WorkflowEdge edge(String from, String to) {
        WorkflowEdge edge = new WorkflowEdge();
        edge.setId(from + "->" + to);
        edge.setFrom(from);
        edge.setTo(to);
        return edge;
    }

    private WorkflowDefinition definition(List<WorkflowNode> nodes, List<WorkflowEdge> edges) {
        WorkflowDefinition def = new WorkflowDefinition();
        def.setNodes(nodes);
        def.setEdges(edges);
        return def;
    }

    private List<ValidationIssue> validate(List<WorkflowNode> nodes, List<WorkflowEdge> edges) {
        return validator.validate(definition(nodes, edges));
    }

    private boolean hasError(List<ValidationIssue> issues, String code) {
        return issues.stream().anyMatch(i -> i.severity() == ValidationIssue.Severity.ERROR
                && code.equals(i.code()));
    }

    private boolean hasWarning(List<ValidationIssue> issues, String code) {
        return issues.stream().anyMatch(i -> i.severity() == ValidationIssue.Severity.WARNING
                && code.equals(i.code()));
    }

    // ------------------------------------------------------------------
    // 环检测
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("环检测（Kahn + DFS 三色，SF-1 重复依赖不误报）")
    class CycleDetection {

        @Test
        @DisplayName("无环图：hasCycle=false")
        void acyclicGraph() {
            List<WorkflowNode> nodes = List.of(node("a", "script-exec"), node("b", "condition"), node("c", "notify"));
            List<WorkflowEdge> edges = List.of(edge("a", "b"), edge("b", "c"));
            assertThat(validator.hasCycle(definition(nodes, edges))).isFalse();
            assertThat(validate(nodes, edges)).isEmpty();
        }

        @Test
        @DisplayName("两节点互环：hasCycle=true 且错误码 GRAPH_CYCLE")
        void twoNodeCycle() {
            List<WorkflowNode> nodes = List.of(node("a", "script-exec"), node("b", "script-exec"));
            List<WorkflowEdge> edges = List.of(edge("a", "b"), edge("b", "a"));
            assertThat(validator.hasCycle(definition(nodes, edges))).isTrue();
            List<ValidationIssue> issues = validate(nodes, edges);
            assertThat(hasError(issues, "GRAPH_CYCLE")).isTrue();
            // 环路径要能指认（a -> b -> a）
            assertThat(issues).anySatisfy(i -> assertThat(i.message()).contains("a -> b"));
        }

        @Test
        @DisplayName("三节点环：找出具体环路径")
        void threeNodeCycle() {
            List<WorkflowNode> nodes = List.of(node("a", "script-exec"), node("b", "condition"), node("c", "notify"));
            List<WorkflowEdge> edges = List.of(edge("a", "b"), edge("b", "c"), edge("c", "a"));
            List<String> cycle = validator.findCyclePath(definition(nodes, edges));
            assertThat(cycle).containsExactlyInAnyOrder("a", "b", "c");
        }

        @Test
        @DisplayName("自环：EDGE_SELF_LOOP（结构校验先于环检测）")
        void selfLoop() {
            List<WorkflowNode> nodes = List.of(node("a", "script-exec"));
            List<WorkflowEdge> edges = List.of(edge("a", "a"));
            assertThat(hasError(validate(nodes, edges), "EDGE_SELF_LOOP")).isTrue();
        }

        @Test
        @DisplayName("SF-1 回归：重复依赖（同一对节点两条同向边）不误报有环")
        void duplicateDependencyIsNotACycle() {
            // DagExecutor.hasCycle 在 dependsOn=[0,0] 时误报有环（inDegree.merge 计数而 adj 用 Set 去重）；
            // 本实现邻接表去重后入度按去重边计数，重复连线只报 EDGE_DUPLICATE 不报环
            List<WorkflowNode> nodes = List.of(node("a", "script-exec"), node("b", "notify"));
            List<WorkflowEdge> edges = new ArrayList<>(List.of(edge("a", "b"), edge("a", "b")));
            assertThat(validator.hasCycle(definition(nodes, edges))).isFalse();
            List<ValidationIssue> issues = validate(nodes, edges);
            assertThat(hasError(issues, "GRAPH_CYCLE")).isFalse();
            assertThat(hasError(issues, "EDGE_DUPLICATE")).isTrue();
        }

        @Test
        @DisplayName("菱形图（fan-in）无环：M1 合法形态是 condition 分叉 + notify 汇聚")
        void diamondGraph() {
            List<WorkflowNode> nodes = List.of(
                    node("start", "script-exec"), node("gate", "condition"),
                    node("yes", "script-exec"), node("no", "script-exec"), node("join", "notify"));
            List<WorkflowEdge> edges = List.of(
                    edge("start", "gate"), edge("gate", "yes"), edge("gate", "no"),
                    edge("yes", "join"), edge("no", "join"));
            assertThat(validator.hasCycle(definition(nodes, edges))).isFalse();
            assertThat(validate(nodes, edges)).isEmpty();
        }

        @Test
        @DisplayName("script-exec 直接扇出到两个下游 → EDGE_TYPE_INCOMPATIBLE（M1 无 parallel 网关）")
        void scriptExecCannotFanOut() {
            List<WorkflowNode> nodes = List.of(
                    node("start", "script-exec"), node("left", "script-exec"), node("join", "notify"));
            List<WorkflowEdge> edges = List.of(
                    edge("start", "left"), edge("start", "join"), edge("left", "join"));
            assertThat(hasError(validate(nodes, edges), "EDGE_TYPE_INCOMPATIBLE")).isTrue();
        }

        @Test
        @DisplayName("空定义 / 空图")
        void emptyGraphs() {
            assertThat(validator.hasCycle(null)).isFalse();
            assertThat(validator.validate(null)).anySatisfy(i -> {
                assertThat(i.severity()).isEqualTo(ValidationIssue.Severity.ERROR);
                assertThat(i.code()).isEqualTo("DEFINITION_EMPTY");
            });
            assertThat(hasError(validate(List.of(), List.of()), "GRAPH_EMPTY")).isTrue();
        }
    }

    // ------------------------------------------------------------------
    // 入口/出口/孤立
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("入口、出口与孤立节点")
    class EntryExitIsolation {

        @Test
        @DisplayName("入口被自环吃掉 → EDGE_SELF_LOOP + GRAPH_NO_ENTRY 同时报告")
        void missingEntry() {
            // 有限 DAG 必然有出口，因此"无出口"只可能伴随环出现；"无入口"则可以由
            // 自环独立造成（a 自环后没有任何节点入度为 0）——两项判定互不依赖
            List<WorkflowNode> nodes = List.of(node("a", "script-exec"), node("b", "notify"));
            List<WorkflowEdge> edges = new ArrayList<>(List.of(edge("a", "a"), edge("a", "b")));
            List<ValidationIssue> issues = validate(nodes, edges);
            assertThat(hasError(issues, "EDGE_SELF_LOOP")).isTrue();
            assertThat(hasError(issues, "GRAPH_NO_ENTRY")).isTrue();
        }

        @Test
        @DisplayName("正常有向无环图：入口与出口判定都通过")
        void entryAndExitPresent() {
            List<WorkflowNode> nodes = List.of(node("a", "script-exec"), node("b", "notify"));
            List<WorkflowEdge> edges = List.of(edge("a", "b"));
            List<ValidationIssue> issues = validate(nodes, edges);
            assertThat(hasError(issues, "GRAPH_NO_ENTRY")).isFalse();
            assertThat(hasError(issues, "GRAPH_NO_EXIT")).isFalse();
        }

        @Test
        @DisplayName("缺失出口（所有节点都有出边）→ GRAPH_NO_EXIT")
        void missingExit() {
            List<WorkflowNode> nodes = List.of(node("a", "script-exec"), node("b", "condition"));
            List<WorkflowEdge> edges = new ArrayList<>(List.of(edge("a", "b"), edge("b", "a")));
            // 有环时环错误优先，但 NO_EXIT 也成立（所有节点都有出边）
            List<ValidationIssue> issues = validate(nodes, edges);
            assertThat(hasError(issues, "GRAPH_CYCLE")).isTrue();
            assertThat(hasError(issues, "GRAPH_NO_EXIT")).isTrue();
        }

        @Test
        @DisplayName("孤立节点（无入无出）→ WARNING NODE_ISOLATED")
        void isolatedNodeWarning() {
            WorkflowNode lonely = node("lonely", "wait");
            lonely.getConfig().put("durationSec", 60);
            List<WorkflowNode> nodes = List.of(node("a", "script-exec"), node("b", "notify"), lonely);
            List<WorkflowEdge> edges = List.of(edge("a", "b"));
            List<ValidationIssue> issues = validate(nodes, edges);
            assertThat(hasWarning(issues, "NODE_ISOLATED")).isTrue();
            assertThat(issues).noneSatisfy(i -> assertThat(i.severity())
                    .isEqualTo(ValidationIssue.Severity.ERROR));
        }

        @Test
        @DisplayName("单节点图：既是入口也是出口，无 ERROR")
        void singleNodeGraph() {
            List<WorkflowNode> nodes = List.of(node("only", "script-exec"));
            assertThat(validate(nodes, List.of())).isEmpty();
            assertThat(validator.hasCycle(definition(nodes, List.of()))).isFalse();
        }

        @Test
        @DisplayName("start -> 中间节点 -> end：拓扑合法，无 sink 警告")
        void startEndTopologyValid() {
            List<WorkflowNode> nodes = List.of(
                    node("begin", "start"), node("work", "script-exec"), node("finish", "end"));
            List<ValidationIssue> issues = validate(nodes,
                    List.of(edge("begin", "work"), edge("work", "finish")));
            assertThat(issues).isEmpty();
        }

        @Test
        @DisplayName("多个 start / start 有入边 → GRAPH_START_INVALID")
        void startRules() {
            List<WorkflowNode> twoStarts = List.of(
                    node("s1", "start"), node("s2", "start"), node("end", "end"));
            List<ValidationIssue> duplicate = validate(twoStarts,
                    List.of(edge("s1", "end"), edge("s2", "end")));
            assertThat(hasError(duplicate, "GRAPH_START_INVALID")).isTrue();

            List<WorkflowNode> incoming = List.of(
                    node("a", "script-exec"), node("begin", "start"), node("finish", "end"));
            List<ValidationIssue> withIncoming = validate(incoming,
                    List.of(edge("a", "begin"), edge("begin", "finish")));
            assertThat(hasError(withIncoming, "GRAPH_START_INVALID")).isTrue();
        }

        @Test
        @DisplayName("end 有出边 / 多个 end → GRAPH_END_INVALID")
        void endRules() {
            List<WorkflowNode> outgoing = List.of(
                    node("begin", "start"), node("finish", "end"), node("tail", "notify"));
            List<ValidationIssue> withOutgoing = validate(outgoing,
                    List.of(edge("begin", "finish"), edge("finish", "tail")));
            assertThat(hasError(withOutgoing, "GRAPH_END_INVALID")).isTrue();

            List<WorkflowNode> twoEnds = List.of(
                    node("begin", "start"), node("e1", "end"), node("e2", "end"));
            List<ValidationIssue> duplicate = validate(twoEnds,
                    List.of(edge("begin", "e1"), edge("begin", "e2")));
            assertThat(hasError(duplicate, "GRAPH_END_INVALID")).isTrue();
        }

        @Test
        @DisplayName("定义里还有其他节点时 end 必须有入边 → GRAPH_END_INVALID")
        void endWithoutInboundEdge() {
            List<WorkflowNode> nodes = List.of(
                    node("begin", "start"), node("work", "script-exec"), node("finish", "end"));
            List<ValidationIssue> issues = validate(nodes,
                    List.of(edge("begin", "work")));
            assertThat(hasError(issues, "GRAPH_END_INVALID")).isTrue();
        }

        @Test
        @DisplayName("存在 end 时其他 sink 只给 WARNING GRAPH_SINK_NOT_END（不阻断旧工作流）")
        void nonEndSinkWarns() {
            WorkflowNode begin = node("begin", "start");
            WorkflowNode gate = node("gate", "condition");
            WorkflowNode sink = node("sink", "notify");
            WorkflowNode finish = node("finish", "end");
            List<ValidationIssue> issues = validate(List.of(begin, gate, sink, finish),
                    List.of(edge("begin", "gate"), edge("gate", "sink"), edge("gate", "finish")));
            assertThat(hasWarning(issues, "GRAPH_SINK_NOT_END")).isTrue();
            assertThat(issues).noneSatisfy(i -> assertThat(i.severity())
                    .isEqualTo(ValidationIssue.Severity.ERROR));
        }
    }

    // ------------------------------------------------------------------
    // 节点与连线结构
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("重复 ID / 未知类型 / 连线端点")
    class NodeAndEdgeStructure {

        @Test
        @DisplayName("nodeKey 重复 → NODE_KEY_DUPLICATE")
        void duplicateNodeKey() {
            List<WorkflowNode> nodes = List.of(node("dup", "script-exec"), node("dup", "notify"));
            assertThat(hasError(validate(nodes, List.of()), "NODE_KEY_DUPLICATE")).isTrue();
        }

        @Test
        @DisplayName("nodeKey 格式不合规（大写/超长/-）→ NODE_KEY_MALFORMED")
        void malformedNodeKey() {
            assertThat(hasError(validate(List.of(node("Bad", "script-exec")), List.of()),
                    "NODE_KEY_MALFORMED")).isTrue();
            assertThat(hasError(validate(List.of(node("a".repeat(65), "script-exec")), List.of()),
                    "NODE_KEY_MALFORMED")).isTrue();
            assertThat(hasError(validate(List.of(node("a-b", "script-exec")), List.of()),
                    "NODE_KEY_MALFORMED")).isTrue();
            assertThat(hasError(validate(List.of(node("_a", "script-exec")), List.of()),
                    "NODE_KEY_MALFORMED")).isTrue();
            assertThat(hasError(validate(List.of(node("a_b1", "script-exec")), List.of()),
                    "NODE_KEY_MALFORMED")).isFalse();
        }

        @Test
        @DisplayName("节点类型未知 → NODE_TYPE_UNKNOWN")
        void unknownNodeType() {
            WorkflowNode node = node("x", "quantum-exec");
            assertThat(hasError(validate(List.of(node), List.of()), "NODE_TYPE_UNKNOWN")).isTrue();
        }

        @Test
        @DisplayName("连线端点不存在 → EDGE_ENDPOINT_MISSING")
        void edgeEndpointMissing() {
            List<WorkflowNode> nodes = List.of(node("a", "script-exec"));
            WorkflowEdge dangling = new WorkflowEdge();
            dangling.setId("dangling");
            dangling.setFrom("a");
            dangling.setTo("ghost");
            assertThat(hasError(validate(nodes, List.of(dangling)), "EDGE_ENDPOINT_MISSING")).isTrue();
        }

        @Test
        @DisplayName("连线 id 重复 → EDGE_ID_DUPLICATE")
        void duplicateEdgeId() {
            List<WorkflowNode> nodes = List.of(node("a", "script-exec"), node("b", "notify"), node("c", "wait"));
            WorkflowEdge first = edge("a", "b");
            WorkflowEdge second = edge("a", "c");
            second.setId(first.getId());
            assertThat(hasError(validate(nodes, List.of(first, second)), "EDGE_ID_DUPLICATE")).isTrue();
        }

        @Test
        @DisplayName("类型不兼容的连线：非 condition 节点多条出边")
        void incompatibleOutEdges() {
            List<WorkflowNode> nodes = List.of(
                    node("a", "script-exec"), node("b", "notify"), node("c", "notify"));
            List<WorkflowEdge> edges = List.of(edge("a", "b"), edge("a", "c"));
            assertThat(hasError(validate(nodes, edges), "EDGE_TYPE_INCOMPATIBLE")).isTrue();
        }

        @Test
        @DisplayName("condition 节点允许 2 条出边（true/false handle），3 条拒绝")
        void conditionHandles() {
            List<WorkflowNode> twoOut = List.of(
                    node("gate", "condition"), node("yes", "notify"), node("no", "notify"));
            List<WorkflowEdge> edges = List.of(edge("gate", "yes"), edge("gate", "no"));
            assertThat(hasError(validate(twoOut, edges), "EDGE_TYPE_INCOMPATIBLE")).isFalse();

            List<WorkflowNode> threeOut = new ArrayList<>(twoOut);
            threeOut.add(node("third", "notify"));
            List<WorkflowEdge> threeEdges = new ArrayList<>(edges);
            threeEdges.add(edge("gate", "third"));
            assertThat(hasError(validate(threeOut, threeEdges), "EDGE_TYPE_INCOMPATIBLE")).isTrue();
        }

        @Test
        @DisplayName("condition 只允许一条出边带 condition（true handle 唯一）")
        void conditionSingleTrueHandle() {
            List<WorkflowNode> nodes = List.of(
                    node("gate", "condition"), node("yes", "notify"), node("no", "notify"));
            WorkflowEdge trueEdge = edge("gate", "yes");
            trueEdge.setCondition("nodes.gate.output.result == true");
            WorkflowEdge falseEdge = edge("gate", "no");
            falseEdge.setCondition("nodes.gate.output.result == false");
            assertThat(hasError(validate(nodes, List.of(trueEdge, falseEdge)),
                    "EDGE_TYPE_INCOMPATIBLE")).isTrue();
        }

        @Test
        @DisplayName("condition sourceHandle=true/false 各一条：合法；旧的 condition 字段允许并存")
        void conditionSourceHandlesValid() {
            List<WorkflowNode> nodes = List.of(
                    node("gate", "condition"), node("yes", "notify"), node("no", "notify"));
            WorkflowEdge trueEdge = edge("gate", "yes");
            trueEdge.setSourceHandle("true");
            trueEdge.setCondition("nodes.gate.output.result == true");
            WorkflowEdge falseEdge = edge("gate", "no");
            falseEdge.setSourceHandle("false");
            // handle 存在时旧 condition 字段允许保留（被 handle 取代），即使两条都带也不报错
            falseEdge.setCondition("nodes.gate.output.result == false");
            assertThat(hasError(validate(nodes, List.of(trueEdge, falseEdge)),
                    "EDGE_TYPE_INCOMPATIBLE")).isFalse();
        }

        @Test
        @DisplayName("condition sourceHandle 重复 true / 非法值 → EDGE_TYPE_INCOMPATIBLE")
        void conditionSourceHandlesInvalid() {
            List<WorkflowNode> three = List.of(
                    node("gate", "condition"), node("a", "notify"), node("b", "notify"), node("c", "notify"));
            WorkflowEdge first = edge("gate", "a");
            first.setSourceHandle("true");
            WorkflowEdge second = edge("gate", "b");
            second.setSourceHandle("true");
            assertThat(hasError(validate(three, List.of(first, second)), "EDGE_TYPE_INCOMPATIBLE"))
                    .isTrue();

            List<WorkflowNode> two = List.of(
                    node("gate", "condition"), node("a", "notify"), node("b", "notify"));
            WorkflowEdge bad = edge("gate", "b");
            bad.setSourceHandle("maybe");
            assertThat(hasError(validate(two, List.of(first, bad)), "EDGE_TYPE_INCOMPATIBLE")).isTrue();
        }
    }

    // ------------------------------------------------------------------
    // outputVar 与表达式
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("outputVar 单写者与表达式校验")
    class OutputVarAndExpressions {

        @Test
        @DisplayName("outputVar 与 nodeKey 冲突（双向）→ NODE_KEY_OUTPUT_VAR_CONFLICT")
        void outputVarConflictsWithNodeKey() {
            WorkflowNode a = node("disk", "script-exec");
            a.setOutputVar("disk");
            assertThat(hasError(validate(List.of(a), List.of()),
                    "NODE_KEY_OUTPUT_VAR_CONFLICT")).isTrue();

            WorkflowNode b = node("check", "script-exec");
            b.setOutputVar("disk");
            WorkflowNode disk = node("disk", "script-exec");
            assertThat(hasError(validate(List.of(b, disk), List.of(edge("disk", "check"))),
                    "NODE_KEY_OUTPUT_VAR_CONFLICT")).isTrue();
        }

        @Test
        @DisplayName("同一 outputVar 多写者 → OUTPUT_VAR_MULTI_WRITER（M1 单写者）")
        void multiWriter() {
            WorkflowNode a = node("a", "script-exec");
            a.setOutputVar("shared");
            WorkflowNode b = node("b", "script-exec");
            b.setOutputVar("shared");
            List<WorkflowNode> nodes = List.of(a, node("join", "notify"), b);
            List<WorkflowEdge> edges = List.of(edge("a", "join"), edge("b", "join"));
            assertThat(hasError(validate(nodes, edges), "OUTPUT_VAR_MULTI_WRITER")).isTrue();
        }

        @Test
        @DisplayName("outputVar 格式不合规 → OUTPUT_VAR_MALFORMED")
        void malformedOutputVar() {
            WorkflowNode a = node("a", "script-exec");
            a.setOutputVar("Bad-Name");
            assertThat(hasError(validate(List.of(a), List.of()), "OUTPUT_VAR_MALFORMED")).isTrue();
        }

        @Test
        @DisplayName("表达式语法错误 → EXPRESSION_SYNTAX")
        void expressionSyntaxError() {
            WorkflowNode gate = node("gate", "condition");
            gate.getConfig().put("expression", "nodes.a.output.x >");
            List<WorkflowNode> nodes = List.of(node("a", "script-exec"), gate);
            List<WorkflowEdge> edges = List.of(edge("a", "gate"));
            assertThat(hasError(validate(nodes, edges), "EXPRESSION_SYNTAX")).isTrue();
        }

        @Test
        @DisplayName("表达式引用非祖先节点 → EXPRESSION_REFERENCE_NOT_ANCESTOR")
        void expressionReferencesNonAncestor() {
            WorkflowNode gate = node("gate", "condition");
            gate.getConfig().put("expression", "nodes.other.output.x > 1");
            List<WorkflowNode> nodes = List.of(node("a", "script-exec"), gate, node("other", "script-exec"));
            List<WorkflowEdge> edges = List.of(edge("a", "gate"), edge("gate", "other"));
            assertThat(hasError(validate(nodes, edges), "EXPRESSION_REFERENCE_NOT_ANCESTOR")).isTrue();
        }

        @Test
        @DisplayName("表达式引用祖先节点 → 通过")
        void expressionReferencesAncestor() {
            WorkflowNode gate = node("gate", "condition");
            gate.getConfig().put("expression", "nodes.a.output.x > 1");
            List<WorkflowNode> nodes = List.of(node("a", "script-exec"), gate, node("b", "notify"));
            List<WorkflowEdge> edges = List.of(edge("a", "gate"), edge("gate", "b"));
            assertThat(validate(nodes, edges)).isEmpty();
        }

        @Test
        @DisplayName("边条件引用非祖先节点同样被拒")
        void edgeConditionReferencesNonAncestor() {
            List<WorkflowNode> nodes = List.of(
                    node("a", "script-exec"), node("b", "notify"), node("c", "notify"),
                    node("other", "script-exec"));
            WorkflowEdge bc = edge("b", "c");
            bc.setCondition("nodes.other.output.x > 1");   // other 与 c 同层，不是祖先
            List<WorkflowEdge> edges = List.of(edge("a", "b"), edge("a", "other"), bc);
            assertThat(hasError(validate(nodes, edges), "EXPRESSION_REFERENCE_NOT_ANCESTOR")).isTrue();
        }

        @Test
        @DisplayName("变量类型未知 → VARIABLE_TYPE_UNKNOWN")
        void unknownVariableType() {
            WorkflowDefinition def = definition(List.of(node("a", "script-exec")), List.of());
            com.easyshell.server.workflow.model.VariableSpec spec =
                    new com.easyshell.server.workflow.model.VariableSpec();
            spec.setType("secret");
            def.getVariables().put("token", spec);
            assertThat(validator.validate(def)).anySatisfy(i ->
                    assertThat(i.code()).isEqualTo("VARIABLE_TYPE_UNKNOWN"));
        }

        @Test
        @DisplayName("transform 映射引用非祖先节点 → EXPRESSION_REFERENCE_NOT_ANCESTOR")
        void transformReferencesNonAncestor() {
            WorkflowNode map = node("map", "transform");
            map.setConfig(new LinkedHashMap<>(Map.of("mappings", List.of(
                    Map.of("name", "usage", "value", "${nodes.other.output.usage}")))));
            List<WorkflowNode> nodes = List.of(
                    node("begin", "start"), map, node("other", "script-exec"), node("finish", "end"));
            List<WorkflowEdge> edges = List.of(
                    edge("begin", "map"), edge("begin", "other"),
                    edge("map", "finish"), edge("other", "finish"));
            assertThat(hasError(validate(nodes, edges), "EXPRESSION_REFERENCE_NOT_ANCESTOR")).isTrue();
        }

        @Test
        @DisplayName("transform 映射引用祖先节点（含 outputVar 别名）→ 通过")
        void transformReferencesAncestorWithAlias() {
            WorkflowNode check = node("check", "script-exec");
            check.setOutputVar("disk");
            WorkflowNode map = node("map", "transform");
            map.setConfig(new LinkedHashMap<>(Map.of("mappings", List.of(
                    Map.of("name", "usage", "value", "${disk.usage}"),
                    Map.of("name", "summary", "value", "使用率 ${nodes.check.output.usage}%")))));
            List<WorkflowNode> nodes = List.of(
                    node("begin", "start"), check, map, node("finish", "end"));
            List<WorkflowEdge> edges = List.of(
                    edge("begin", "check"), edge("check", "map"), edge("map", "finish"));
            assertThat(validate(nodes, edges)).isEmpty();
        }

        @Test
        @DisplayName("AI input 纯路径 / ${path} 引用非祖先节点 → EXPRESSION_REFERENCE_NOT_ANCESTOR")
        void aiInputReferencesNonAncestor() {
            WorkflowNode aiPath = node("ai", "ai-analyze");
            aiPath.setConfig(new LinkedHashMap<>(Map.of(
                    "instructions", "分析",
                    "input", "${nodes.other.output.text}")));
            List<WorkflowNode> nodes = List.of(
                    node("begin", "start"), aiPath, node("other", "script-exec"), node("finish", "end"));
            List<WorkflowEdge> edges = List.of(
                    edge("begin", "ai"), edge("begin", "other"),
                    edge("ai", "finish"), edge("other", "finish"));
            assertThat(hasError(validate(nodes, edges), "EXPRESSION_REFERENCE_NOT_ANCESTOR")).isTrue();

            WorkflowNode aiBarePath = node("ai", "ai-analyze");
            aiBarePath.setConfig(new LinkedHashMap<>(Map.of(
                    "instructions", "分析",
                    "input", "nodes.other.output")));
            List<WorkflowNode> bareNodes = List.of(
                    node("begin", "start"), aiBarePath, node("other", "script-exec"), node("finish", "end"));
            assertThat(hasError(validate(bareNodes, edges), "EXPRESSION_REFERENCE_NOT_ANCESTOR")).isTrue();
        }

        @Test
        @DisplayName("表达式用 outputVar 别名引用非祖先 → EXPRESSION_REFERENCE_NOT_ANCESTOR")
        void aliasExpressionReferencesNonAncestor() {
            WorkflowNode begin = node("begin", "start");
            WorkflowNode gate = node("gate", "condition");
            gate.setConfig(new LinkedHashMap<>(Map.of("expression", "disk.usage > 90")));
            WorkflowNode check = node("check", "script-exec");
            check.setOutputVar("disk");
            WorkflowNode finish = node("finish", "end");
            List<ValidationIssue> issues = validate(List.of(begin, gate, check, finish),
                    List.of(edge("begin", "gate"), edge("begin", "check"),
                            edge("gate", "finish"), edge("check", "finish")));
            assertThat(hasError(issues, "EXPRESSION_REFERENCE_NOT_ANCESTOR")).isTrue();
        }

        @Test
        @DisplayName("表达式用括号路径引用非祖先 → EXPRESSION_REFERENCE_NOT_ANCESTOR")
        void bracketPathReferencesNonAncestor() {
            WorkflowNode begin = node("begin", "start");
            WorkflowNode gate = node("gate", "condition");
            gate.setConfig(new LinkedHashMap<>(Map.of("expression", "nodes['other'].output.x > 1")));
            WorkflowNode other = node("other", "script-exec");
            WorkflowNode finish = node("finish", "end");
            List<ValidationIssue> issues = validate(List.of(begin, gate, other, finish),
                    List.of(edge("begin", "gate"), edge("begin", "other"),
                            edge("gate", "finish"), edge("other", "finish")));
            assertThat(hasError(issues, "EXPRESSION_REFERENCE_NOT_ANCESTOR")).isTrue();
        }

        @Test
        @DisplayName("script-exec parameters 模板引用非祖先 → EXPRESSION_REFERENCE_NOT_ANCESTOR")
        void scriptParametersReferenceNonAncestor() {
            WorkflowNode begin = node("begin", "start");
            WorkflowNode run = node("run", "script-exec");
            run.setConfig(new LinkedHashMap<>(Map.of(
                    "script", "echo {{VALUE}}",
                    "parameters", Map.of("VALUE", "${nodes.other.output.x}"))));
            WorkflowNode other = node("other", "script-exec");
            WorkflowNode finish = node("finish", "end");
            List<ValidationIssue> issues = validate(List.of(begin, run, other, finish),
                    List.of(edge("begin", "run"), edge("begin", "other"),
                            edge("run", "finish"), edge("other", "finish")));
            assertThat(hasError(issues, "EXPRESSION_REFERENCE_NOT_ANCESTOR")).isTrue();
        }

        @Test
        @DisplayName("notify messageTemplate / parameters 引用非祖先 → EXPRESSION_REFERENCE_NOT_ANCESTOR")
        void notifyTemplatesReferenceNonAncestor() {
            WorkflowNode begin = node("begin", "start");
            WorkflowNode tell = node("tell", "notify");
            tell.setConfig(new LinkedHashMap<>(Map.of(
                    "channels", List.of("telegram"),
                    "messageTemplate", "${nodes.other.output.x}",
                    "parameters", Map.of("FINAL", "${nodes.other.output}"))));
            WorkflowNode other = node("other", "script-exec");
            WorkflowNode finish = node("finish", "end");
            List<ValidationIssue> issues = validate(List.of(begin, tell, other, finish),
                    List.of(edge("begin", "tell"), edge("begin", "other"),
                            edge("tell", "finish"), edge("other", "finish")));
            assertThat(hasError(issues, "EXPRESSION_REFERENCE_NOT_ANCESTOR")).isTrue();
        }

        @Test
        @DisplayName("approval title / detailTemplate / parameters 引用非祖先 → EXPRESSION_REFERENCE_NOT_ANCESTOR")
        void approvalTemplatesReferenceNonAncestor() {
            WorkflowNode begin = node("begin", "start");
            WorkflowNode ask = node("ask", "approval");
            ask.setConfig(new LinkedHashMap<>(Map.of(
                    "title", "${nodes.other.output.x}",
                    "detailTemplate", "${nodes.other.output}",
                    "parameters", Map.of("P", "${nodes.other.output}"))));
            WorkflowNode other = node("other", "script-exec");
            WorkflowNode finish = node("finish", "end");
            List<ValidationIssue> issues = validate(List.of(begin, ask, other, finish),
                    List.of(edge("begin", "ask"), edge("begin", "other"),
                            edge("ask", "finish"), edge("other", "finish")));
            assertThat(hasError(issues, "EXPRESSION_REFERENCE_NOT_ANCESTOR")).isTrue();
        }

        @Test
        @DisplayName("rawParams 直引 ai-analyze 输出 → AI_OUTPUT_RAW_FORBIDDEN")
        void rawParamsCannotReferenceAiOutput() {
            WorkflowNode begin = node("begin", "start");
            WorkflowNode ai = node("ai", "ai-analyze");
            ai.setConfig(new LinkedHashMap<>(Map.of("instructions", "分析")));
            WorkflowNode run = node("run", "script-exec");
            run.setConfig(new LinkedHashMap<>(Map.of(
                    "script", "run {{BODY:raw}}",
                    "rawParams", List.of("BODY"),
                    "parameters", Map.of("BODY", "${nodes.ai.output.text}"))));
            WorkflowNode finish = node("finish", "end");
            List<ValidationIssue> issues = validate(List.of(begin, ai, run, finish),
                    List.of(edge("begin", "ai"), edge("ai", "run"), edge("run", "finish")));
            assertThat(hasError(issues, "EXPRESSION_REFERENCE_NOT_ANCESTOR")).isFalse();
            assertThat(hasError(issues, "AI_OUTPUT_RAW_FORBIDDEN")).isTrue();
        }

        @Test
        @DisplayName("wait 节点 durationSec 越界 → NODE_CONFIG_INVALID")
        void waitDurationOutOfRange() {
            WorkflowNode wait = node("pause", "wait");
            wait.getConfig().put("durationSec", 99999);
            assertThat(hasError(validate(List.of(wait), List.of()), "NODE_CONFIG_INVALID")).isTrue();

            WorkflowNode ok = node("pause", "wait");
            ok.getConfig().put("durationSec", 30);
            assertThat(hasError(validate(List.of(ok), List.of()), "NODE_CONFIG_INVALID")).isFalse();
        }
    }

    // ------------------------------------------------------------------
    // 祖先集合
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("祖先集合（运行期授权与发布期引用校验共用）")
    class Ancestors {

        @Test
        @DisplayName("传递前驱：菱形图上 join 的祖先是全部三个上游")
        void transitiveAncestors() {
            WorkflowDefinition def = definition(List.of(
                    node("start", "script-exec"), node("left", "script-exec"),
                    node("right", "script-exec"), node("join", "notify")),
                    List.of(edge("start", "left"), edge("start", "right"),
                            edge("left", "join"), edge("right", "join")));
            Map<String, Set<String>> ancestors = validator.ancestorMap(def);
            assertThat(ancestors.get("join")).containsExactlyInAnyOrder("start", "left", "right");
            assertThat(ancestors.get("left")).containsExactly("start");
            assertThat(ancestors.get("start")).isEmpty();
            assertThat(validator.isAncestor(def, "start", "join")).isTrue();
            assertThat(validator.isAncestor(def, "join", "start")).isFalse();
        }

        @Test
        @DisplayName("含环的图上祖先计算不抛异常（环边被忽略而不是死循环）")
        void ancestorsWithCycle() {
            WorkflowDefinition def = definition(
                    List.of(node("a", "script-exec"), node("b", "script-exec")),
                    List.of(edge("a", "b"), edge("b", "a")));
            assertThat(validator.ancestorMap(def)).containsKeys("a", "b");
        }
    }
}
