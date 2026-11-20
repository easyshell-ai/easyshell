package com.easyshell.server.workflow.gate;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DispatchIntentHmacTest {
    private final DispatchIntentHmac hmac = new DispatchIntentHmac(
            Base64.getEncoder().encodeToString(new byte[32]));
    private final DispatchIntentHmac.Target a = new DispatchIntentHmac.Target("a", 1);
    private final DispatchIntentHmac.Target b = new DispatchIntentHmac.Target("b", 2);

    @Test
    void intentBindsEveryFieldAndCanonicalizesTargetOrder() {
        String script = hmac.scriptHmac("secret command");
        String digest = hmac.intentHmac("run", "node", 1, 2, script, List.of(a, b), 30);
        assertThat(hmac.intentHmac("run", "node", 1, 2, script, List.of(b, a), 30)).isEqualTo(digest);
        assertThat(hmac.intentHmac("other", "node", 1, 2, script, List.of(a, b), 30)).isNotEqualTo(digest);
        assertThat(hmac.intentHmac("run", "other", 1, 2, script, List.of(a, b), 30)).isNotEqualTo(digest);
        assertThat(hmac.intentHmac("run", "node", 2, 2, script, List.of(a, b), 30)).isNotEqualTo(digest);
        assertThat(hmac.intentHmac("run", "node", 1, 3, script, List.of(a, b), 30)).isNotEqualTo(digest);
        assertThat(hmac.intentHmac("run", "node", 1, 2, hmac.scriptHmac("other"), List.of(a, b), 30)).isNotEqualTo(digest);
        assertThat(hmac.intentHmac("run", "node", 1, 2, script, List.of(a), 30)).isNotEqualTo(digest);
        assertThat(hmac.intentHmac("run", "node", 1, 2, script, List.of(a, new DispatchIntentHmac.Target("b", 3)), 30)).isNotEqualTo(digest);
        assertThat(hmac.intentHmac("run", "node", 1, 2, script, List.of(a, b), 31)).isNotEqualTo(digest);
        assertThat(DispatchIntentHmac.matches(digest, digest)).isTrue();
        assertThat(DispatchIntentHmac.matches(digest, null)).isFalse();
        assertThat(DispatchIntentHmac.matches(digest, digest.toUpperCase())).isFalse();
    }

    @Test
    void rejectsMissingKeyAndAmbiguousInputsWithoutExposingScript() {
        assertThatThrownBy(() -> new DispatchIntentHmac(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DispatchIntentHmac("not base64!")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DispatchIntentHmac("YQ==")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> hmac.intentHmac("run", "node", 1, 1, hmac.scriptHmac("secret command"), List.of(a, a), 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("secret command");
        assertThatThrownBy(() -> hmac.intentHmac("run", "node", 1, 1, null, List.of(a), 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
