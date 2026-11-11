package com.easyshell.server.ai.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SensitiveDataFilterTest {
    private final SensitiveDataFilter filter = new SensitiveDataFilter();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void masksJsonRecursivelyAndKeepsItValid() throws Exception {
        String result = filter.filter("{\"password\":\"short\",\"nested\":[{\"apiKey\":7,\"sk-abcdefghijklmnopqrst\":\"ok\"}],\"text\":\"token=abc\"}");
        assertThat(result).doesNotContain("short", "abcdefghijklmnopqrst", "abc");
        assertThat(mapper.readTree(result).get("password").asText()).isEqualTo("***");
        assertThat(mapper.readTree(result).get("nested").get(0).get("apiKey").asText()).isEqualTo("***");
    }

    @Test
    void collisionsAndMalformedJsonFailClosed() throws Exception {
        String input = "{\"sk-abcdefghijklmnopqrst\":1,\"sk-abcdefghijklmnopqrstu\":2}";
        assertThat(filter.filter(input)).isEqualTo("***");
        assertThatThrownBy(() -> filter.filterJson(mapper.readValue(input, Object.class)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("abcdefghijkl");
        assertThat(filter.filter("{\"password\":\"short\"")).isEqualTo("***");
        assertThat(filter.filter("{\"password\":\"short\",\"password\":\"other\"}")).isEqualTo("***");
    }

    @Test
    void ordinaryTextStillMasks() {
        assertThat(filter.filter("hello password=short world")).contains("hello", "world", "***").doesNotContain("short");
    }
}
