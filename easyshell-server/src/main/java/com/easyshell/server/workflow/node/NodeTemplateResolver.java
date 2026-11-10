package com.easyshell.server.workflow.node;

import com.easyshell.server.workflow.engine.NodeContext;
import com.easyshell.server.workflow.expression.ExpressionEvaluator;
import com.easyshell.server.workflow.expression.ExpressionException;
import com.easyshell.server.workflow.model.ValueType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * llm / classifier / code 的模板解析（与 ai-analyze / DagValidator 的静态引用识别保持一致）。
 *
 * <p>纯路径（无 {@code ${}}）按一条路径取值并字符串化；其余走
 * {@link ExpressionEvaluator#renderTemplate}。code 的输入若整串正好是一个
 * {@code ${path}}，保留原始类型（number/boolean/json），让数据变换拿到结构化值。</p>
 */
final class NodeTemplateResolver {

    /** 与 DagValidator.PURE_PATH 保持一致 */
    private static final Pattern PURE_PATH = Pattern.compile(
            "^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$");

    private static final Pattern EXACT_TEMPLATE = Pattern.compile("^\\$\\{([^{}]+)}$");

    private NodeTemplateResolver() {
    }

    static String resolveText(String template, NodeContext ctx, ExpressionEvaluator evaluator) {
        String source = template == null ? "" : template;
        String trimmed = source.trim();
        if (!source.contains("${") && PURE_PATH.matcher(trimmed).matches()) {
            String path = ExpressionEvaluator.normalizePath(trimmed);
            Object value = ctx.resolver().resolve(path)
                    .orElseThrow(() -> new ExpressionException(ExpressionException.REFERENCE_NOT_FOUND,
                            "引用的变量不存在: " + path));
            List<String> warnings = new ArrayList<>();
            String rendered = ValueType.renderToString(value, warnings);
            warnings.forEach(ctx.variables()::addWarning);
            return rendered;
        }
        return evaluator.renderTemplate(source, Map.of(), ctx.resolver(), ctx.variables()::addWarning);
    }

    /**
     * 解析 code 节点的输入值：整串只有一个 {@code ${path}} 时保留原始类型，
     * 其余模板渲染为字符串（含字面量）。
     */
    static Object resolveValue(String template, NodeContext ctx, ExpressionEvaluator evaluator) {
        String source = template == null ? "" : template;
        Matcher exact = EXACT_TEMPLATE.matcher(source.trim());
        if (exact.matches()) {
            String path = ExpressionEvaluator.normalizePath(exact.group(1).trim());
            return ctx.resolver().resolve(path)
                    .orElseThrow(() -> new ExpressionException(ExpressionException.REFERENCE_NOT_FOUND,
                            "引用的变量不存在: " + path));
        }
        return evaluator.renderTemplate(source, Map.of(), ctx.resolver(), ctx.variables()::addWarning);
    }
}
