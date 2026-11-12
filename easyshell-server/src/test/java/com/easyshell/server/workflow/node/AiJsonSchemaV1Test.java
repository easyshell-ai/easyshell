package com.easyshell.server.workflow.node;

import com.easyshell.server.ai.security.SensitiveDataFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiJsonSchemaV1Test {
    private final ObjectMapper mapper = new ObjectMapper();
    private final SensitiveDataFilter filter = new SensitiveDataFilter();
    private final String schema = """
            {"type":"object","properties":{"risk":{"type":"string","enum":["high","low"]},
            "scores":{"type":"array","items":{"type":"integer"}}},
            "required":["risk","scores"],"additionalProperties":false}
            """;

    @Test void validAndInvalidValues() {
        AiJsonSchemaV1 validator = new AiJsonSchemaV1(schema, mapper);
        assertThat(validator.parseAndMask("{\"risk\":\"high\",\"scores\":[1,2]}", filter))
                .isEqualTo(Map.of("risk", "high", "scores", java.util.List.of(1, 2)));
        for (String invalid : java.util.List.of("{}", "{\"risk\":\"other\",\"scores\":[]}",
                "{\"risk\":\"high\",\"scores\":[1.5]}", "{\"risk\":\"high\",\"scores\":[],\"extra\":1}",
                "{\"risk\":\"high\",\"risk\":\"low\",\"scores\":[]}",
                "{\"risk\":\"high\",\"scores\":[]} true")) {
            assertThatThrownBy(() -> validator.parseAndMask(invalid, filter)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void rejectsUnsupportedSchemaAndOversizeEnvelope() {
        for (String invalid : java.util.List.of("{}", "{\"$ref\":\"https://example.com\"}",
                "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":true}",
                "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false,\"description\":\"x\"}")) {
            assertThatThrownBy(() -> new AiJsonSchemaV1(invalid, mapper)).isInstanceOf(IllegalArgumentException.class);
        }
        AiJsonSchemaV1 validator = new AiJsonSchemaV1(schema, mapper);
        assertThatThrownBy(() -> validator.checkEnvelope(Map.of("text", "中".repeat(3000))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void sensitiveSchemaIsRejectedBeforeOutbound() {
        String secretSchema = "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\",\"enum\":[\"token=abc123\"]}},\"required\":[\"x\"],\"additionalProperties\":false}";
        String fieldSchema = "{\"type\":\"object\",\"properties\":{\"password\":{\"type\":\"string\"}},\"additionalProperties\":false}";
        for (String candidate : java.util.List.of(secretSchema, fieldSchema)) {
            assertThatThrownBy(() -> new AiJsonSchemaV1(candidate, mapper))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid strict JSON schema or output");
        }
    }

    @Test void strictParsingIgnoresPermissiveMapperAndNumbersRemainExact() {
        ObjectMapper permissive = new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_COMMENTS)
                .enable(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_SINGLE_QUOTES);
        String numeric = "{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"integer\",\"enum\":[9007199254740993]}},\"additionalProperties\":false}";
        AiJsonSchemaV1 validator = new AiJsonSchemaV1(numeric, permissive);
        assertThat(validator.parseAndMask("{\"n\":9007199254740993.0}", filter)).isNotNull();
        for (String invalid : java.util.List.of("{'n':9007199254740993}", "{/*comment*/\"n\":9007199254740993}",
                "{\"n\":9007199254740992.0}", "{\"n\":9007199254740993.1}")) {
            assertThatThrownBy(() -> validator.parseAndMask(invalid, filter)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new AiJsonSchemaV1("{'type':'object'}", permissive)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void masksSensitiveStructuredFieldRegardlessOfValueType() {
        Object masked = AiStructuredOutput.maskJson(Map.of("password", "x", "credentials", Map.of("x", 123),
                "safe", java.util.List.of(Map.of("api_key", 123))), filter);
        assertThat(masked).isEqualTo(Map.of("password", "***", "credentials", "***",
                "safe", java.util.List.of(Map.of("api_key", "***"))));
    }
}
