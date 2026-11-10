package com.easyshell.server.workflow.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 工作流连线（definition_json 的 edges[] 元素）。
 *
 * <p><b>门控优先级</b>：上游是 condition 且边带 {@code sourceHandle ∈ {true,false}} 时，
 * 按上游 {@code output.result} 的布尔值门控（true handle 只在 result=true 时通行，
 * false handle 只在 result=false 时通行；见 WorkflowEngine）。{@code sourceHandle} 缺省时
 * 沿用旧的 {@code condition} 表达式门控（缺省 = 恒真），旧定义完全兼容。
 * 表达式字段保留在 condition 出边上也不影响 handle 门控——handle 存在即取代它。</p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = false)
public class WorkflowEdge {

    private String id;

    /** from = 上游 nodeKey */
    private String from;

    /** to = 下游 nodeKey */
    private String to;

    /**
     * 上游输出把手：condition 节点为 {@code true}/{@code false}，其他节点通常为
     * {@code out}（可省略）。持久化在 definition_json 内，无数据库迁移。
     */
    private String sourceHandle;

    /** 表达式（§2.5 语法）；null/空 = 恒真通行；sourceHandle 存在时被取代 */
    private String condition;

    /** 本边的上游失败策略，覆盖节点的 onFailure 与 definition.defaultOnUpstreamFailure */
    private String onUpstreamFailure;
}
