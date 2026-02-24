package com.easyshell.server.ai.orchestrator;

import com.easyshell.server.ai.config.AgenticConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TokenEstimator {

    private static final int MESSAGE_OVERHEAD_TOKENS = 4;

    private final AgenticConfigService configService;

    public int estimate(String text) {
        if (text == null || text.isEmpty()) return 0;
        double charsPerToken = configService.getDouble("ai.context.chars-per-token", 3.0);
        return (int) Math.ceil(text.length() / charsPerToken);
    }

    public int estimateMessage(Message message) {
        String content = message.getText();
        return estimate(content) + MESSAGE_OVERHEAD_TOKENS;
    }
}
