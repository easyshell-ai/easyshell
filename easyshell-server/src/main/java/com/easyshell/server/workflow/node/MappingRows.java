package com.easyshell.server.workflow.node;

import com.easyshell.server.workflow.engine.NodeFailedException;
import com.easyshell.server.workflow.expression.ExpressionEvaluator;
import com.easyshell.server.workflow.expression.ExpressionException;
import com.easyshell.server.workflow.expression.VariableResolver;
import com.easyshell.server.workflow.graph.ValidationIssue;
import com.easyshell.server.workflow.model.WorkflowNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * transform {@code config.mappings} 与 end {@code config.outputs} 的共用解析逻辑。
 *
 * <p><b>解析语义（与设计 §2.5 一致）</b>：值恰好是 {@code ${path}} 时保留解析结果的
 * 原类型（对象/数组/数字/布尔不字符串化）；否则按模板插值
 * {@code expressionEvaluator.renderTemplate} 渲染。引用不存在一律 fail-closed，
 * 抛 {@link ExpressionException#REFERENCE_NOT_FOUND}（执行器再包装为
 * {@link NodeFailedException} 并保留错误码）。</p>
 */
final class MappingRows {

    /** transform 映射名的安全字符集：小写标识符，避免与 JSONPath / 模板变量混淆 */
    static final Pattern NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");

    private static final Pattern EXACT_TEMPLATE = Pattern.compile("^\\$\\{([^{}]+)}$");

    private MappingRows() {
    }

    record Row(String name, String value) {
    }

    /**
     * 校验配置并返回结构合法的行（非法行不进入返回值，问题写进 issues）。
     *
     * @param namePattern      映射名格式；null = 只要求非空
     * @param minRows          最少行数（end 允许 0）
     * @param maxRows          最多行数（上限 50）
     * @param valueNonBlank    value 是否必须非空
     */
    static List<Row> validate(WorkflowNode node, String configKey, String label,
                              Pattern namePattern, int minRows, int maxRows, boolean valueNonBlank,
                              ValidationIssue.Sink issues) {
        Object raw = node.getConfig() == null ? null : node.getConfig().get(configKey);
        List<?> list;
        if (raw == null) {
            list = List.of();   // 缺省按空数组：end 允许无输出映射；transform 会在行数下限报错
        } else if (raw instanceof List<?> value) {
            list = value;
        } else {
            issues.error("NODE_CONFIG_INVALID",
                    label + " 的 " + configKey + " 必须是数组", node.getKey());
            return List.of();
        }
        if (list.size() > maxRows) {
            issues.error("NODE_CONFIG_INVALID",
                    label + " 的 " + configKey + " 最多 " + maxRows + " 行，实际 " + list.size(),
                    node.getKey());
        }
        if (list.size() < minRows) {
            issues.error("NODE_CONFIG_INVALID",
                    label + " 的 " + configKey + " 至少 " + minRows + " 行，实际 " + list.size(),
                    node.getKey());
        }
        List<Row> rows = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        int index = 0;
        for (Object item : list) {
            index++;
            if (!(item instanceof Map<?, ?> map)) {
                issues.error("NODE_CONFIG_INVALID",
                        label + " 的 " + configKey + "[" + (index - 1) + "] 必须是对象 {name, value}",
                        node.getKey());
                continue;
            }
            Object rawName = map.get("name");
            Object rawValue = map.get("value");
            if (!(rawName instanceof String name) || name.isBlank()) {
                issues.error("NODE_CONFIG_INVALID",
                        label + " 的 " + configKey + "[" + (index - 1) + "].name 必须是非空字符串",
                        node.getKey());
                continue;
            }
            if (namePattern != null && !namePattern.matcher(name).matches()) {
                issues.error("NODE_CONFIG_INVALID",
                        label + " 的 " + configKey + "[" + (index - 1) + "].name '" + name
                                + "' 不合规：必须匹配 " + namePattern.pattern(),
                        node.getKey());
                continue;
            }
            if (!names.add(name)) {
                issues.error("NODE_CONFIG_INVALID",
                        label + " 的 " + configKey + " 存在重复 name: " + name, node.getKey());
                continue;
            }
            if (!(rawValue instanceof String value)) {
                issues.error("NODE_CONFIG_INVALID",
                        label + " 的 " + configKey + "[" + (index - 1) + "].value 必须是字符串",
                        node.getKey());
                continue;
            }
            if (valueNonBlank && value.isBlank()) {
                issues.error("NODE_CONFIG_INVALID",
                        label + " 的 " + configKey + "[" + (index - 1) + "].value 不能为空",
                        node.getKey());
                continue;
            }
            rows.add(new Row(name, value));
        }
        return rows;
    }

    /** 执行期复用同一套校验：任何 ERROR 直接失败（fail-closed） */
    static List<Row> requireValid(WorkflowNode node, String configKey, String label,
                                  Pattern namePattern, int minRows, int maxRows, boolean valueNonBlank) {
        ValidationIssue.Sink sink = new ValidationIssue.Sink();
        List<Row> rows = validate(node, configKey, label, namePattern, minRows, maxRows,
                valueNonBlank, sink);
        if (sink.hasError()) {
            ValidationIssue first = sink.issues().stream()
                    .filter(i -> i.severity() == ValidationIssue.Severity.ERROR)
                    .findFirst().orElse(null);
            String message = first == null ? label + " 配置不合规" : first.message();
            throw new NodeFailedException("NODE_CONFIG_INVALID", message);
        }
        return rows;
    }

    /** 解析全部映射；引用不存在抛 {@link ExpressionException}（fail-closed） */
    static Map<String, Object> resolve(List<Row> rows, ExpressionEvaluator expressionEvaluator,
                                       VariableResolver resolver, Consumer<String> warnings) {
        Map<String, Object> output = new LinkedHashMap<>();
        for (Row row : rows) {
            output.put(row.name(), resolveValue(row.value(), expressionEvaluator, resolver, warnings));
        }
        return output;
    }

    /**
     * 单值解析：纯 {@code ${path}} 保留类型，其余按模板插值。
     */
    static Object resolveValue(String template, ExpressionEvaluator expressionEvaluator,
                               VariableResolver resolver, Consumer<String> warnings) {
        if (template == null) {
            throw new ExpressionException(ExpressionException.REFERENCE_NOT_FOUND,
                    "映射值为 null");
        }
        String trimmed = template.trim();
        Matcher exact = EXACT_TEMPLATE.matcher(trimmed);
        if (exact.matches()) {
            String path = ExpressionEvaluator.normalizePath(exact.group(1).trim());
            Optional<Object> resolved = resolver.resolve(path);
            if (resolved.isEmpty()) {
                throw new ExpressionException(ExpressionException.REFERENCE_NOT_FOUND,
                        "引用的变量不存在: " + path);
            }
            return resolved.get();
        }
        return expressionEvaluator.renderTemplate(trimmed, Map.of(), resolver, warnings);
    }
}
