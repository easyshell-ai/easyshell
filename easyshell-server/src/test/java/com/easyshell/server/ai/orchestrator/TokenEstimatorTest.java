package com.easyshell.server.ai.orchestrator;

import com.easyshell.server.ai.config.AgenticConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TokenEstimator")
class TokenEstimatorTest {

    @Mock
    private AgenticConfigService configService;

    private TokenEstimator estimator;

    @BeforeEach
    void setUp() {
        lenient().when(configService.getDouble("ai.context.chars-per-token", 3.0)).thenReturn(3.0);
        estimator = new TokenEstimator(configService);
    }

    @Test
    void nullText_returnsZero() {
        assertThat(estimator.estimate(null)).isZero();
    }

    @Test
    void emptyText_returnsZero() {
        assertThat(estimator.estimate("")).isZero();
    }

    @Test
    void helloText_returnsCeil() {
        // "hello" = 5 chars, 5/3.0 = 1.67 → ceil = 2
        assertThat(estimator.estimate("hello")).isEqualTo(2);
    }

    @Test
    void exactMultiple_noCeil() {
        // "abcdef" = 6 chars, 6/3.0 = 2.0 → ceil = 2
        assertThat(estimator.estimate("abcdef")).isEqualTo(2);
    }

    @Test
    void singleChar_returnsOne() {
        // "a" = 1 char, 1/3.0 = 0.33 → ceil = 1
        assertThat(estimator.estimate("a")).isEqualTo(1);
    }

    @Test
    void chineseText_countsByCharLength() {
        // "你好世界" = 4 Java chars (String.length() == 4), 4/3.0 = 1.33 → ceil = 2
        assertThat(estimator.estimate("你好世界")).isEqualTo(2);
    }

    @Test
    void longerText() {
        // 10 chars, 10/3.0 = 3.33 → ceil = 4
        assertThat(estimator.estimate("1234567890")).isEqualTo(4);
    }

    @Test
    void estimateMessage_addsOverhead() {
        // UserMessage("hello") → estimate("hello") = 2, + 4 overhead = 6
        UserMessage msg = new UserMessage("hello");
        assertThat(estimator.estimateMessage(msg)).isEqualTo(6);
    }

    @Test
    void estimateMessage_emptyContent() {
        UserMessage msg = new UserMessage("");
        // estimate("") = 0, + 4 overhead = 4
        assertThat(estimator.estimateMessage(msg)).isEqualTo(4);
    }

    @Test
    void customCharsPerToken() {
        // Override to 4.0 chars per token
        when(configService.getDouble("ai.context.chars-per-token", 3.0)).thenReturn(4.0);
        TokenEstimator custom = new TokenEstimator(configService);

        // "hello" = 5 chars, 5/4.0 = 1.25 → ceil = 2
        assertThat(custom.estimate("hello")).isEqualTo(2);
        // "12345678" = 8 chars, 8/4.0 = 2.0 → ceil = 2
        assertThat(custom.estimate("12345678")).isEqualTo(2);
    }
}
