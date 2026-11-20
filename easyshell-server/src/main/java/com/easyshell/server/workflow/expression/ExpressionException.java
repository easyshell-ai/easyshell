package com.easyshell.server.workflow.expression;

import lombok.Getter;

/**
 * 表达式求值异常（fail-closed 的唯一出口）。
 *
 * <p>{@link ExpressionEvaluator#evaluate} 只有 "true" 与抛异常两种返回；
 * 调用方捕获后按 fail-closed 处置（边条件 → 该边不可通行；节点条件 → 记录 warning）。
 * 与 AI Chat 的 {@code ConditionEvaluator} 的 fail-open（{@code return true}）是刻意分叉。</p>
 */
@Getter
public class ExpressionException extends RuntimeException {

    /** 语法错误（含超长/超深/禁用 token） */
    public static final String SYNTAX = "EXPRESSION_SYNTAX_ERROR";

    /** 引用的变量不存在（上游失败即不存在，§2.2 ①） */
    public static final String REFERENCE_NOT_FOUND = "EXPRESSION_REFERENCE_NOT_FOUND";

    /** 类型不匹配（无可行的显式转换） */
    public static final String TYPE_MISMATCH = "EXPRESSION_TYPE_MISMATCH";

    /** 超出资源上限（长度/AST 深度） */
    public static final String LIMIT = "EXPRESSION_LIMIT_EXCEEDED";

    /** 命中禁用 token（Runtime/ProcessBuilder/now()/random() 等，纵深防御） */
    public static final String FORBIDDEN = "EXPRESSION_FORBIDDEN_TOKEN";

    private final String code;

    public ExpressionException(String code, String message) {
        super(message);
        this.code = code;
    }

    public ExpressionException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }
}
