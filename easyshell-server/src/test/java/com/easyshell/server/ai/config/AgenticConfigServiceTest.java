package com.easyshell.server.ai.config;

import com.easyshell.server.model.entity.SystemConfig;
import com.easyshell.server.repository.SystemConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgenticConfigService")
class AgenticConfigServiceTest {

    @Mock
    private SystemConfigRepository repository;

    private AgenticConfigService service;

    @BeforeEach
    void setUp() {
        service = new AgenticConfigService(repository, new ObjectMapper());
    }

    private SystemConfig configWith(String key, String value) {
        SystemConfig config = new SystemConfig();
        config.setConfigKey(key);
        config.setConfigValue(value);
        return config;
    }

    @Nested
    @DisplayName("get(String)")
    class GetString {
        @Test
        void returnsDbValue() {
            when(repository.findByConfigKey("my.key")).thenReturn(Optional.of(configWith("my.key", "hello")));
            assertThat(service.get("my.key", "default")).isEqualTo("hello");
        }

        @Test
        void returnsDefaultWhenMissing() {
            when(repository.findByConfigKey("missing")).thenReturn(Optional.empty());
            assertThat(service.get("missing", "fallback")).isEqualTo("fallback");
        }
    }

    @Nested
    @DisplayName("getInt")
    class GetInt {
        @Test
        void parsesValidInt() {
            when(repository.findByConfigKey("k")).thenReturn(Optional.of(configWith("k", "20")));
            assertThat(service.getInt("k", 10)).isEqualTo(20);
        }

        @Test
        void parsesIntWithWhitespace() {
            when(repository.findByConfigKey("k")).thenReturn(Optional.of(configWith("k", "  42  ")));
            assertThat(service.getInt("k", 10)).isEqualTo(42);
        }

        @Test
        void returnsDefaultOnInvalidInt() {
            when(repository.findByConfigKey("k")).thenReturn(Optional.of(configWith("k", "not_a_number")));
            assertThat(service.getInt("k", 10)).isEqualTo(10);
        }

        @Test
        void returnsDefaultWhenMissing() {
            when(repository.findByConfigKey("k")).thenReturn(Optional.empty());
            assertThat(service.getInt("k", 99)).isEqualTo(99);
        }
    }

    @Nested
    @DisplayName("getDouble")
    class GetDouble {
        @Test
        void parsesValidDouble() {
            when(repository.findByConfigKey("k")).thenReturn(Optional.of(configWith("k", "3.5")));
            assertThat(service.getDouble("k", 3.0)).isEqualTo(3.5);
        }

        @Test
        void returnsDefaultOnInvalid() {
            when(repository.findByConfigKey("k")).thenReturn(Optional.of(configWith("k", "abc")));
            assertThat(service.getDouble("k", 3.0)).isEqualTo(3.0);
        }

        @Test
        void returnsDefaultWhenMissing() {
            when(repository.findByConfigKey("k")).thenReturn(Optional.empty());
            assertThat(service.getDouble("k", 2.5)).isEqualTo(2.5);
        }
    }

    @Nested
    @DisplayName("getBoolean")
    class GetBoolean {
        @Test
        void trueValues() {
            for (String val : List.of("true", "1", "yes", "on", "TRUE", "Yes", "ON")) {
                when(repository.findByConfigKey("k")).thenReturn(Optional.of(configWith("k", val)));
                assertThat(service.getBoolean("k", false))
                        .as("Should be true for '%s'", val)
                        .isTrue();
            }
        }

        @Test
        void falseValues() {
            for (String val : List.of("false", "0", "no", "off", "random")) {
                when(repository.findByConfigKey("k")).thenReturn(Optional.of(configWith("k", val)));
                assertThat(service.getBoolean("k", true))
                        .as("Should be false for '%s'", val)
                        .isFalse();
            }
        }

        @Test
        void returnsDefaultWhenMissing() {
            when(repository.findByConfigKey("k")).thenReturn(Optional.empty());
            assertThat(service.getBoolean("k", true)).isTrue();
            assertThat(service.getBoolean("k", false)).isFalse();
        }
    }

    @Nested
    @DisplayName("getStringSet")
    class GetStringSet {
        @Test
        void parsesJsonArray() {
            when(repository.findByConfigKey("k")).thenReturn(Optional.of(configWith("k", "[\"rm\",\"mkfs\",\"dd\"]")));
            Set<String> result = service.getStringSet("k", Collections.emptySet());
            assertThat(result).containsExactlyInAnyOrder("rm", "mkfs", "dd");
        }

        @Test
        void returnsDefaultOnEmptyArray() {
            when(repository.findByConfigKey("k")).thenReturn(Optional.of(configWith("k", "[]")));
            Set<String> defaults = Set.of("a", "b");
            assertThat(service.getStringSet("k", defaults)).isEqualTo(defaults);
        }

        @Test
        void returnsDefaultOnEmptyString() {
            when(repository.findByConfigKey("k")).thenReturn(Optional.of(configWith("k", "  ")));
            Set<String> defaults = Set.of("x");
            assertThat(service.getStringSet("k", defaults)).isEqualTo(defaults);
        }

        @Test
        void returnsDefaultOnInvalidJson() {
            when(repository.findByConfigKey("k")).thenReturn(Optional.of(configWith("k", "not json")));
            Set<String> defaults = Set.of("fallback");
            assertThat(service.getStringSet("k", defaults)).isEqualTo(defaults);
        }

        @Test
        void returnsDefaultWhenMissing() {
            when(repository.findByConfigKey("k")).thenReturn(Optional.empty());
            Set<String> defaults = Set.of("default");
            assertThat(service.getStringSet("k", defaults)).isEqualTo(defaults);
        }
    }

    @Nested
    @DisplayName("getStringList")
    class GetStringList {
        @Test
        void parsesJsonArray() {
            when(repository.findByConfigKey("k")).thenReturn(Optional.of(configWith("k", "[\"a\",\"b\",\"c\"]")));
            List<String> result = service.getStringList("k", Collections.emptyList());
            assertThat(result).containsExactly("a", "b", "c");
        }

        @Test
        void returnsDefaultOnInvalidJson() {
            when(repository.findByConfigKey("k")).thenReturn(Optional.of(configWith("k", "{bad")));
            List<String> defaults = List.of("fallback");
            assertThat(service.getStringList("k", defaults)).isEqualTo(defaults);
        }
    }

    @Nested
    @DisplayName("getLong")
    class GetLong {
        @Test
        void parsesValidLong() {
            when(repository.findByConfigKey("k")).thenReturn(Optional.of(configWith("k", "100000")));
            assertThat(service.getLong("k", 0L)).isEqualTo(100000L);
        }

        @Test
        void returnsDefaultOnInvalid() {
            when(repository.findByConfigKey("k")).thenReturn(Optional.of(configWith("k", "xyz")));
            assertThat(service.getLong("k", 42L)).isEqualTo(42L);
        }

        @Test
        void returnsDefaultWhenMissing() {
            when(repository.findByConfigKey("k")).thenReturn(Optional.empty());
            assertThat(service.getLong("k", 7L)).isEqualTo(7L);
        }
    }
}
