package com.easyshell.server.workflow.expression;

import java.util.Optional;

/**
 * 变量解析入口（表达式与模板的唯一取数通道）。
 *
 * <p>实现方（{@code VariableStore}）负责作用域规则：
 * {@code run.input.*} / {@code run.workflowName|id|startedBy|triggerType} /
 * {@code nodes.<key>.output.<path>} / {@code nodes.<key>.status|durationMs|attempt} /
 * {@code <outputVar>.<path>}（outputVar 别名，与 nodes.<key>.output 始终等价，MF-11）。</p>
 *
 * <p>不存在一律返回 {@link Optional#empty()}——由求值方决定是抛
 * {@link ExpressionException#REFERENCE_NOT_FOUND}（fail-closed）还是按 null 处理，
 * 绝不把"不存在"静默变成空串。</p>
 */
public interface VariableResolver {

    Optional<Object> resolve(String path);
}
