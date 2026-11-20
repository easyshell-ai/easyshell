package com.easyshell.server.workflow.model;

import java.util.List;
import java.util.Map;

/**
 * 工作流类型系统（§2.7，M1 四种：string / number / boolean / json）。
 *
 * <p>四条硬规则：不静默截断、不静默转换、host-list 执行前校验、错误必须带
 * nodeKey + attempt + runId。仅有的两条显式转换：
 * ① condition 比较时一侧 number、另一侧可解析为 number 的 string → 数值比较；
 * ② 模板渲染时 number/boolean 渲染为字符串，null 渲染为空串并记 warning
 * （该空串随后会被 L1 以 PARAM_UNSAFE 拒绝——fail-closed，刻意行为）。</p>
 */
public enum ValueType {

    STRING("string"),
    NUMBER("number"),
    BOOLEAN("boolean"),
    JSON("json");

    private final String code;

    ValueType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** VariableSpec.type → 枚举；未知类型返回 null（发布期 ERROR） */
    public static ValueType fromSpec(String code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case "string" -> STRING;
            case "number" -> NUMBER;
            case "boolean" -> BOOLEAN;
            case "json" -> JSON;
            default -> null;
        };
    }

    /**
     * 校验一个值是否属于本类型。
     *
     * @return null = 合法；非 null = 不合法的原因（actual 描述，供错误信息使用）
     */
    public String validate(Object value) {
        return switch (this) {
            case STRING -> value instanceof String ? null : "expected string, actual " + describe(value);
            case NUMBER -> asDouble(value) != null ? null : "expected number, actual " + describe(value);
            case BOOLEAN -> value instanceof Boolean ? null : "expected boolean, actual " + describe(value);
            case JSON -> (value instanceof Map<?, ?> || value instanceof List<?>) || (value instanceof String s && looksLikeJson(s))
                    ? null
                    : "expected json, actual " + describe(value);
        };
    }

    /** 把入参规整成该类型的规范形态（Double / Boolean / String / 解析后的 JSON）；不合法返回 null */
    public Object normalize(Object value) {
        return switch (this) {
            case STRING -> value instanceof String s ? s : null;
            case NUMBER -> asDouble(value);
            case BOOLEAN -> value instanceof Boolean b ? b : null;
            case JSON -> {
                if (value instanceof Map<?, ?> || value instanceof List<?>) {
                    yield value;
                }
                if (value instanceof String s && looksLikeJson(s)) {
                    try {
                        yield new com.fasterxml.jackson.databind.ObjectMapper().readTree(s);
                    } catch (Exception e) {
                        yield null;
                    }
                }
                yield null;
            }
        };
    }

    private static boolean looksLikeJson(String s) {
        String t = s.trim();
        return (t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"));
    }

    /** 值的可读描述（用于错误信息，绝不把值当命令或表达式解释） */
    public static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String s) {
            return "string (\"" + (s.length() > 64 ? s.substring(0, 64) + "..." : s) + "\")";
        }
        if (value instanceof Boolean b) {
            return "boolean (" + b + ")";
        }
        if (value instanceof Number n) {
            return "number (" + n + ")";
        }
        if (value instanceof Map<?, ?>) {
            return "json(object)";
        }
        if (value instanceof List<?>) {
            return "json(array)";
        }
        return value.getClass().getSimpleName();
    }

    /**
     * 尝试把值按 double 解释（显式转换①：可解析为 number 的 string 参与数值比较）。
     * NaN / Infinity 一律不接受（§2.7 number 禁 NaN/Infinity）。
     */
    public static Double asDouble(Object value) {
        if (value instanceof Number n) {
            double d = n.doubleValue();
            return Double.isFinite(d) ? d : null;
        }
        if (value instanceof String s) {
            String t = s.trim();
            if (t.isEmpty()) {
                return null;
            }
            try {
                double d = Double.parseDouble(t);
                return Double.isFinite(d) ? d : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 模板渲染用的字符串化（显式转换②）。
     *
     * @param warnings 非 null 时收集 warning（null 值渲染为空串会记一条 warning）
     */
    public static String renderToString(Object value, List<String> warnings) {
        if (value == null) {
            if (warnings != null) {
                warnings.add("变量值为 null，已渲染为空串（该空串随后会被值策略拒绝）");
            }
            return "";
        }
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Boolean b) {
            return b.toString();
        }
        if (value instanceof Number n) {
            if (n instanceof Double d) {
                if (Double.isFinite(d) && d == Math.floor(d) && Math.abs(d) < 1e15) {
                    return String.valueOf(d.longValue());
                }
                return d.toString();
            }
            if (n instanceof Float f) {
                double d = f.doubleValue();
                if (d == Math.floor(d) && Math.abs(d) < 1e15) {
                    return String.valueOf((long) d);
                }
                return Float.toString(f);
            }
            return n.toString();
        }
        // json 形态：序列化为紧凑 JSON（下游按需 JsonPath 读取）
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
