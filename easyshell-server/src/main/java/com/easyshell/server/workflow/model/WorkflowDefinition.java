package com.easyshell.server.workflow.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流定义（workflow_version.definition_json 的反序列化形态，schema 见设计 §4.1）。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = false)
public class WorkflowDefinition {

    private Integer schemaVersion;

    /** abort / skip / partial，默认 skip（§2.8） */
    private String defaultOnUpstreamFailure;

    /** 审批被拒后的 run 级行为：abort(默认) / continue（§3.8 边界③） */
    private String onRejection;

    /** 触发入参规格：name -> VariableSpec */
    private Map<String, VariableSpec> variables = new LinkedHashMap<>();

    private List<WorkflowNode> nodes = new ArrayList<>();

    private List<WorkflowEdge> edges = new ArrayList<>();

    // ------------------------------------------------------------------
    // 图查询
    // ------------------------------------------------------------------

    public WorkflowNode nodeByKey(String key) {
        if (key == null) {
            return null;
        }
        return nodes.stream().filter(n -> key.equals(n.getKey())).findFirst().orElse(null);
    }

    public List<WorkflowEdge> inEdges(String nodeKey) {
        List<WorkflowEdge> result = new ArrayList<>();
        for (WorkflowEdge edge : edges) {
            if (nodeKey.equals(edge.getTo())) {
                result.add(edge);
            }
        }
        return result;
    }

    public List<WorkflowEdge> outEdges(String nodeKey) {
        List<WorkflowEdge> result = new ArrayList<>();
        for (WorkflowEdge edge : edges) {
            if (nodeKey.equals(edge.getFrom())) {
                result.add(edge);
            }
        }
        return result;
    }

    public boolean hasNode(String key) {
        return nodeByKey(key) != null;
    }

    /** 上游失败策略的 run 级默认值（缺省 skip，§2.8） */
    public String defaultOnUpstreamFailureOrSkip() {
        return defaultOnUpstreamFailure == null || defaultOnUpstreamFailure.isBlank()
                ? FailurePolicy.SKIP.code() : defaultOnUpstreamFailure;
    }

    /** 审批被拒后的 run 级行为（缺省 abort，§3.8） */
    public String onRejectionOrAbort() {
        return onRejection == null || onRejection.isBlank() ? "abort" : onRejection;
    }
}
