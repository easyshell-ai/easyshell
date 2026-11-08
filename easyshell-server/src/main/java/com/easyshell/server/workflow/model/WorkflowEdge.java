package com.easyshell.server.workflow.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 工作流连线（definition_json 的 edges[] 元素）。
 *
 * <p>边的 {@code condition} 是唯一门控（§2.11 SF-8）：缺省 = 恒真；
 * condition 节点自身 expression 只决定从 true/false 哪个 handle 出边，
 * 其结果作为 {@code output.result} 与边条件求值输入。</p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = false)
public class WorkflowEdge {

    private String id;

    /** from = 上游 nodeKey */
    private String from;

    /** to = 下游 nodeKey */
    private String to;

    /** 表达式（§2.5 语法）；null/空 = 恒真通行 */
    private String condition;

    /** 本边的上游失败策略，覆盖节点的 onFailure 与 definition.defaultOnUpstreamFailure */
    private String onUpstreamFailure;
}
