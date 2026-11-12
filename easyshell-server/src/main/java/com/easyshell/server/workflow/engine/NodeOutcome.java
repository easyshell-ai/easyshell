package com.easyshell.server.workflow.engine;

/**
 * 节点执行结果。
 *
 * <p>WAITING 表示"本节点自行挂起"——实现必须已落库（approval 行 + node_run.status=waiting）
 * 且不占线程；抛任何异常由调用方统一转 FAILED。</p>
 */
public enum NodeOutcome {

    SUCCEEDED,
    FAILED,
    SKIPPED,
    WAITING
}
