package com.easyshell.server.workflow.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 类型系统单测（§2.7：string / number / boolean / json 四种 + 四条报错硬规则 +
 * 仅有的两条显式转换）。
 */
@DisplayName("类型系统 — 校验规则与显式转换")
class ValueTypeTest {

    @Nested
    @DisplayName("类型校验")
    class Validation {

        @Test
        @DisplayName("string：只接受 String")
        void stringType() {
            assertThat(ValueType.STRING.validate("ok")).isNull();
            assertThat(ValueType.STRING.validate(1)).isNotNull();
            assertThat(ValueType.STRING.validate(true)).isNotNull();
            assertThat(ValueType.STRING.validate(Map.of())).isNotNull();
        }

        @Test
        @DisplayName("number：接受数字与可解析字符串，拒 NaN/Infinity/布尔")
        void numberType() {
            assertThat(ValueType.NUMBER.validate(90)).isNull();
            assertThat(ValueType.NUMBER.validate(90.5)).isNull();
            assertThat(ValueType.NUMBER.validate("91.2")).isNull();
            assertThat(ValueType.NUMBER.validate("91.2%")).isNotNull();
            assertThat(ValueType.NUMBER.validate(true)).isNotNull();
            assertThat(ValueType.NUMBER.validate(Double.NaN)).isNotNull();
            assertThat(ValueType.NUMBER.validate(Double.POSITIVE_INFINITY)).isNotNull();
            assertThat(ValueType.NUMBER.validate("")).isNotNull();
        }

        @Test
        @DisplayName("boolean：严格 true/false，不接受 \"true\" 字符串")
        void booleanType() {
            assertThat(ValueType.BOOLEAN.validate(true)).isNull();
            assertThat(ValueType.BOOLEAN.validate(false)).isNull();
            assertThat(ValueType.BOOLEAN.validate("true")).isNotNull();
            assertThat(ValueType.BOOLEAN.validate(1)).isNotNull();
        }

        @Test
        @DisplayName("json：接受对象/数组与合法 JSON 文本")
        void jsonType() {
            assertThat(ValueType.JSON.validate(Map.of("a", 1))).isNull();
            assertThat(ValueType.JSON.validate(List.of(1, 2))).isNull();
            assertThat(ValueType.JSON.validate("{\"a\":1}")).isNull();
            assertThat(ValueType.JSON.validate("[1,2]")).isNull();
            assertThat(ValueType.JSON.validate("not json")).isNotNull();
            assertThat(ValueType.JSON.validate(42)).isNotNull();
        }

        @Test
        @DisplayName("fromSpec：未知类型返回 null（发布期 ERROR）")
        void fromSpec() {
            assertThat(ValueType.fromSpec("string")).isEqualTo(ValueType.STRING);
            assertThat(ValueType.fromSpec("number")).isEqualTo(ValueType.NUMBER);
            assertThat(ValueType.fromSpec("boolean")).isEqualTo(ValueType.BOOLEAN);
            assertThat(ValueType.fromSpec("json")).isEqualTo(ValueType.JSON);
            assertThat(ValueType.fromSpec("secret")).isNull();
            assertThat(ValueType.fromSpec("file")).isNull();
            assertThat(ValueType.fromSpec("host-list")).isNull();
            assertThat(ValueType.fromSpec(null)).isNull();
        }
    }

    @Nested
    @DisplayName("显式转换")
    class Conversions {

        @Test
        @DisplayName("转换①：可解析为 number 的 string 参与数值比较")
        void numericCoercion() {
            assertThat(ValueType.asDouble("91.2")).isEqualTo(91.2);
            assertThat(ValueType.asDouble(90)).isEqualTo(90.0);
            assertThat(ValueType.asDouble("91.2%")).isNull();
            assertThat(ValueType.asDouble("abc")).isNull();
            assertThat(ValueType.asDouble(true)).isNull();
            assertThat(ValueType.asDouble(Double.NaN)).isNull();
        }

        @Test
        @DisplayName("转换②：number/boolean 渲染为字符串，null 渲染为空串并记 warning")
        void renderToString() {
            List<String> warnings = new ArrayList<>();
            assertThat(ValueType.renderToString(93.2, null)).isEqualTo("93.2");
            assertThat(ValueType.renderToString(90.0, null)).isEqualTo("90");
            assertThat(ValueType.renderToString(90, null)).isEqualTo("90");
            assertThat(ValueType.renderToString(true, null)).isEqualTo("true");
            assertThat(ValueType.renderToString("text", null)).isEqualTo("text");
            assertThat(ValueType.renderToString(null, warnings)).isEqualTo("");
            assertThat(warnings).hasSize(1);
        }

        @Test
        @DisplayName("normalize：把入参规整为规范形态")
        void normalize() {
            assertThat(ValueType.NUMBER.normalize("91.2")).isEqualTo(91.2);
            assertThat(ValueType.STRING.normalize("x")).isEqualTo("x");
            assertThat(ValueType.BOOLEAN.normalize(Boolean.TRUE)).isEqualTo(Boolean.TRUE);
            assertThat(ValueType.NUMBER.normalize("abc")).isNull();
            assertThat(ValueType.JSON.normalize("{\"a\":1}")).isInstanceOf(com.fasterxml.jackson.databind.JsonNode.class);
        }
    }

    @Nested
    @DisplayName("错误信息（四条硬规则：不静默截断/不静默转换/带 nodeKey+attempt+runId）")
    class ErrorMessages {

        @Test
        @DisplayName("describe 给出 expected/actual，可读且不带命令语义")
        void describe() {
            assertThat(ValueType.describe("91.2%")).isEqualTo("string (\"91.2%\")");
            assertThat(ValueType.describe(true)).isEqualTo("boolean (true)");
            assertThat(ValueType.describe(90)).isEqualTo("number (90)");
            assertThat(ValueType.describe(null)).isEqualTo("null");
            assertThat(ValueType.describe(Map.of())).isEqualTo("json(object)");
            assertThat(ValueType.describe(List.of())).isEqualTo("json(array)");
            // 超长值截断展示，不把整个值塞进错误信息
            assertThat(ValueType.describe("x".repeat(200))).endsWith("...\")");
        }
    }
}
