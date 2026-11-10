package com.easyshell.server.workflow.node;

import com.easyshell.server.ai.security.SensitiveDataFilter;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM/classifier 的结构化输出解析工具（工作流 2.1）。
 *
 * <p>模型经常把 JSON 包在 Markdown 代码块里；这里只做"去围栏 + Jackson 解析"，
 * 不猜语义、不静默修补。解析失败由调用方决定是否 repair 或 fail-closed。</p>
 */
final class AiStructuredOutput {

    private AiStructuredOutput() {
    }

    /** 去掉 ```json / ``` 围栏（只处理整段包裹，保留内容原样）。 */
    static String stripCodeFences(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstNewline = trimmed.indexOf('\n');
        if (firstNewline < 0) {
            return trimmed;
        }
        String body = trimmed.substring(firstNewline + 1);
        int closing = body.lastIndexOf("```");
        if (closing >= 0) {
            body = body.substring(0, closing);
        }
        return body.trim();
    }

    /** Jackson 解析为通用 Java 值（Map/List/标量）。空串或非法 JSON 抛异常。 */
    static Object parseJson(String text, ObjectMapper objectMapper) throws Exception {
        String json = stripCodeFences(text);
        if (json.isBlank()) {
            throw new IllegalArgumentException("模型返回为空，无法解析 JSON");
        }
        return objectMapper.readValue(json, Object.class);
    }

    /** 供输出字段展示的模型标签：provider/model；都缺省时为 default。 */
    static String modelLabel(String provider, String model) {
        boolean noProvider = provider == null || provider.isBlank() || "default".equals(provider);
        boolean noModel = model == null || model.isBlank() || "default".equals(model);
        if (noProvider && noModel) {
            return "default";
        }
        String resolvedProvider = noProvider ? "default" : provider.trim();
        String resolvedModel = noModel ? "default" : model.trim();
        return resolvedProvider + "/" + resolvedModel;
    }

    /**
     * 递归脱敏结构化值中的字符串（复用 {@link SensitiveDataFilter}，不建立第二套掩码实现）。
     * 先解析模型原文再脱敏，避免掩码把 JSON 结构改坏。
     */
    static Object maskJson(Object value, SensitiveDataFilter filter) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return filter.filter(text);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> masked = new LinkedHashMap<>();
            map.forEach((key, item) -> masked.put(String.valueOf(key), maskJson(item, filter)));
            return masked;
        }
        if (value instanceof List<?> list) {
            List<Object> masked = new ArrayList<>();
            for (Object item : list) {
                masked.add(maskJson(item, filter));
            }
            return masked;
        }
        return value;
    }

    /** json 模式追加到 system prompt 的输出纪律与可选 schema 提示。 */
    static String jsonInstruction(String jsonSchema) {
        StringBuilder sb = new StringBuilder();
        sb.append("输出格式硬性要求：只输出一个合法的 JSON 值，不要输出 Markdown 代码块、")
                .append("解释文字或前后缀。");
        if (jsonSchema != null && !jsonSchema.isBlank()) {
            sb.append("\nJSON 必须符合以下 JSON Schema（仅作为输出约束，不会在服务端二次校验）：\n")
                    .append(jsonSchema.trim());
        }
        return sb.toString();
    }
}
