package com.easyshell.server.workflow.expression;

import java.util.List;

/**
 * 表达式 import 禁用闸（B5 H1 的运行时一半；源码扫描那一半由
 * {@code ExpressionImportGuardTest} 锁定）。
 *
 * <p>M1 用自研白名单递归下降解析器而不是 SpEL/OGNL——{@code T(java.lang.Runtime)...}
 * 是现成 RCE。本类是第二道纵深防御：即便未来解析器被改宽，
 * 任何表达式里出现"反射/进程/环境/时间随机"语义的 token 都直接拒绝（fail-closed）。</p>
 */
public final class ExpressionImportGuard {

    /** 禁用 token（大小写不敏感；结构字符由词法分析器负责，这里只管"语义类"token） */
    private static final List<String> FORBIDDEN_TOKENS = List.of(
            "runtime", "processbuilder", "getclass", "class.forname", "forname",
            "java.lang", "java.io", "java.nio", "java.net",
            "system.exit", "system.getenv", "system.getproperty", "system.clearproperty",
            "reflect.", "thread.", "scriptengine", "classloader",
            "now(", "random(", "uuid(", "currenttimemillis", "nanotime",
            "eval(", "exec(", "t(", "new ", "import "
    );

    private ExpressionImportGuard() {
    }

    /**
     * 检查表达式是否含禁用 token。
     *
     * @throws ExpressionException 命中任意一条即拒绝（code = EXPRESSION_FORBIDDEN_TOKEN）
     */
    public static void check(String expression) {
        if (expression == null) {
            return;
        }
        String lower = expression.toLowerCase(java.util.Locale.ROOT);
        for (String token : FORBIDDEN_TOKENS) {
            if (lower.contains(token)) {
                throw new ExpressionException(ExpressionException.FORBIDDEN,
                        "表达式含禁用语义 '" + token.trim() + "'，已拒绝求值");
            }
        }
    }

    /** 测试与文档用：当前禁用 token 全表 */
    public static List<String> forbiddenTokens() {
        return FORBIDDEN_TOKENS;
    }
}
