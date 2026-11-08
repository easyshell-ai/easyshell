package com.easyshell.server.workflow.engine;

/**
 * 节点执行失败（携带稳定错误码，供 {@code workflow_node_run.error_message} 与审计使用）。
 *
 * <p>错误码分族：{@code PARAM_*}（值越界/槽位不合规/缺参数，§2.4.5）、
 * {@code SUBSTITUTION_REJECTED} / {@code PARAM_RAW_UNSAFE}（L3）、
 * {@code RISK_BLOCKED}（L4 门禁）、{@code NODE_TIMEOUT}、{@code APPROVAL_*}、
 * {@code EXPRESSION_*}（转发自 {@code ExpressionException}）、
 * {@code TYPE_MISMATCH}（§2.7 类型系统）。</p>
 */
public class NodeFailedException extends RuntimeException {

    private final String code;

    public NodeFailedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public NodeFailedException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
