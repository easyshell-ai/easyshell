package com.easyshell.server.ai.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.jayway.jsonpath.JsonPath;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class DataFormatTool {

    private final ObjectMapper objectMapper;
    private final YAMLMapper yamlMapper;

    public DataFormatTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
        this.yamlMapper = new YAMLMapper();
    }

    @Tool(description = "在不同数据格式之间转换。支持 JSON、YAML、Properties 格式。")
    public String convertFormat(
            @ToolParam(description = "要转换的数据") String data,
            @ToolParam(description = "源格式：json/yaml/properties") String fromFormat,
            @ToolParam(description = "目标格式：json/yaml/properties") String toFormat) {
        try {
            if (data == null || data.isBlank()) return "数据不能为空";
            if (fromFormat == null || toFormat == null) return "请指定源格式和目标格式";

            String from = fromFormat.trim().toLowerCase();
            String to = toFormat.trim().toLowerCase();

            // Parse input
            Object parsed = switch (from) {
                case "json" -> objectMapper.readValue(data, Object.class);
                case "yaml" -> yamlMapper.readValue(data, Object.class);
                case "properties" -> parseProperties(data);
                default -> throw new IllegalArgumentException("不支持的源格式：" + fromFormat + "，支持 json/yaml/properties");
            };

            // Convert to output
            String result = switch (to) {
                case "json" -> objectMapper.writeValueAsString(parsed);
                case "yaml" -> yamlMapper.writeValueAsString(parsed);
                case "properties" -> toProperties(parsed);
                default -> throw new IllegalArgumentException("不支持的目标格式：" + toFormat + "，支持 json/yaml/properties");
            };

            return String.format("格式转换成功（%s → %s）：\n```%s\n%s\n```", from, to, to, result);
        } catch (Exception e) {
            return "格式转换失败：" + e.getMessage();
        }
    }

    @Tool(description = "格式化 JSON 字符串，使其更易读。")
    public String prettyPrintJson(
            @ToolParam(description = "要格式化的 JSON 字符串") String json) {
        try {
            if (json == null || json.isBlank()) return "JSON 不能为空";

            Object parsed = objectMapper.readValue(json, Object.class);
            String pretty = objectMapper.writeValueAsString(parsed);
            return "格式化结果：\n```json\n" + pretty + "\n```";
        } catch (Exception e) {
            return "JSON 格式化失败：" + e.getMessage() + "\n请确认输入是有效的 JSON";
        }
    }

    @Tool(description = "使用 JSONPath 表达式从 JSON 中提取数据。")
    public String extractJsonPath(
            @ToolParam(description = "JSON 数据") String json,
            @ToolParam(description = "JSONPath 表达式，如 '$.store.book[0].title' 或 '$..author'") String jsonPath) {
        try {
            if (json == null || json.isBlank()) return "JSON 数据不能为空";
            if (jsonPath == null || jsonPath.isBlank()) return "JSONPath 表达式不能为空";

            Object result = JsonPath.read(json, jsonPath);
            String resultStr = objectMapper.writeValueAsString(result);

            if (resultStr.length() > 8000) {
                resultStr = resultStr.substring(0, 8000) + "\n... [结果已截断]";
            }

            return String.format("JSONPath 查询结果（%s）：\n%s", jsonPath, resultStr);
        } catch (Exception e) {
            return "JSONPath 查询失败：" + e.getMessage();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseProperties(String data) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String line : data.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue;
            int eqIdx = line.indexOf('=');
            if (eqIdx > 0) {
                map.put(line.substring(0, eqIdx).trim(), line.substring(eqIdx + 1).trim());
            }
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private String toProperties(Object obj) {
        if (obj instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) obj;
            StringBuilder sb = new StringBuilder();
            flattenMap("", map, sb);
            return sb.toString();
        }
        return "# Cannot convert non-object type to properties format";
    }

    @SuppressWarnings("unchecked")
    private void flattenMap(String prefix, Map<String, Object> map, StringBuilder sb) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map) {
                flattenMap(key, (Map<String, Object>) value, sb);
            } else {
                sb.append(key).append("=").append(value).append("\n");
            }
        }
    }
}
