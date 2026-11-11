package com.easyshell.server.workflow.node;

import com.easyshell.server.ai.security.SensitiveDataFilter;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** Deliberately small, closed JSON Schema dialect; never resolves references. */
public final class AiJsonSchemaV1 {
    static final String MODE = "json_schema_v1";
    private static final int LIMIT = 8192;
    private static final SensitiveDataFilter SCHEMA_FILTER = new SensitiveDataFilter();
    private final ObjectMapper mapper;
    private final JsonNode schema;

    public AiJsonSchemaV1(Object rawSchema, ObjectMapper source) {
        // Do not inherit permissive parser features or numeric coercions from the application mapper.
        mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        if (!(rawSchema instanceof String text) || bytes(text) > LIMIT) throw invalid();
        try {
            schema = mapper.readTree(text);
            int[] count = {0};
            checkSchema(schema, 0, count);
            if (!"object".equals(schema.path("type").asText())) throw invalid();
        } catch (Exception e) {
            throw invalid();
        }
    }

    private static int bytes(String s) { return s.getBytes(StandardCharsets.UTF_8).length; }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid strict JSON schema or output"); }

    private void checkSchema(JsonNode s, int depth, int[] count) {
        if (s == null || !s.isObject() || depth > 8 || ++count[0] > 128) throw invalid();
        String type = s.path("type").asText("");
        if (!Set.of("object", "array", "string", "number", "integer", "boolean").contains(type)) throw invalid();
        Set<String> allowed = new HashSet<>(Set.of("type", "enum"));
        if (type.equals("object")) allowed.addAll(Set.of("properties", "required", "additionalProperties"));
        if (type.equals("array")) allowed.add("items");
        s.fieldNames().forEachRemaining(key -> { if (!allowed.contains(key)) throw invalid(); });
        if (s.has("enum")) {
            JsonNode values = s.get("enum");
            if (!values.isArray() || values.isEmpty() || values.size() > 64) throw invalid();
            for (JsonNode value : values) {
                if (!matchesType(type, value) || value.isContainerNode()) throw invalid();
                if (value.isTextual() && !value.textValue().equals(SCHEMA_FILTER.filter(value.textValue()))) throw invalid();
            }
        }
        if (type.equals("object")) {
            JsonNode props = s.get("properties");
            if (props == null || !props.isObject() || props.size() > 64) throw invalid();
            if (!s.has("additionalProperties") || !s.get("additionalProperties").isBoolean()
                    || s.get("additionalProperties").booleanValue()) throw invalid();
            props.fields().forEachRemaining(e -> {
                if (e.getKey().isEmpty() || e.getKey().length() > 128
                        || SCHEMA_FILTER.isSensitiveField(e.getKey())
                        || !e.getKey().equals(SCHEMA_FILTER.filter(e.getKey()))) throw invalid();
                checkSchema(e.getValue(), depth + 1, count);
            });
            if (s.has("required")) {
                JsonNode required = s.get("required");
                if (!required.isArray() || required.size() > 64) throw invalid();
                Set<String> seen = new HashSet<>();
                for (JsonNode r : required) if (!r.isTextual() || !props.has(r.textValue()) || !seen.add(r.textValue())) throw invalid();
            }
        }
        if (type.equals("array")) checkSchema(s.get("items"), depth + 1, count);
    }

    private static boolean matchesType(String type, JsonNode v) {
        return switch (type) {
            case "object" -> v.isObject();
            case "array" -> v.isArray();
            case "string" -> v.isTextual();
            case "number" -> v.isNumber();
            case "integer" -> v.isNumber() && v.decimalValue().stripTrailingZeros().scale() <= 0;
            case "boolean" -> v.isBoolean();
            default -> false;
        };
    }

    private void checkValue(JsonNode s, JsonNode v, int depth, int[] count, SensitiveDataFilter filter) {
        if (depth > 8 || ++count[0] > 1024 || !matchesType(s.path("type").asText(), v)) throw invalid();
        if (s.has("enum")) {
            boolean found = false;
            for (JsonNode candidate : s.get("enum")) {
                if (candidate.isNumber() && v.isNumber()
                        ? candidate.decimalValue().compareTo(v.decimalValue()) == 0 : candidate.equals(v)) found = true;
            }
            if (!found) throw invalid();
        }
        if (v.isObject()) {
            JsonNode props = s.get("properties");
            if (s.has("required")) for (JsonNode r : s.get("required")) if (!v.has(r.textValue())) throw invalid();
            Iterator<Map.Entry<String, JsonNode>> fields = v.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!field.getKey().equals(filter.filter(field.getKey())) || !props.has(field.getKey())) throw invalid();
                checkValue(props.get(field.getKey()), field.getValue(), depth + 1, count, filter);
            }
        } else if (v.isArray()) {
            for (JsonNode item : v) checkValue(s.get("items"), item, depth + 1, count, filter);
        }
    }

    Object parseAndMask(String raw, SensitiveDataFilter filter) {
        if (raw == null || bytes(raw) > LIMIT) throw invalid();
        try {
            JsonNode parsed = mapper.readTree(raw);
            if (parsed == null) throw invalid();
            Object masked = AiStructuredOutput.maskJson(mapper.treeToValue(parsed, Object.class), filter);
            JsonNode result = mapper.valueToTree(masked);
            checkValue(schema, result, 0, new int[]{0}, filter);
            return masked;
        } catch (Exception e) { throw invalid(); }
    }

    void checkEnvelope(Map<String, Object> output) {
        try {
            if (mapper.writeValueAsBytes(output).length > LIMIT) throw invalid();
        } catch (Exception e) { throw invalid(); }
    }
}
