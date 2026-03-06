package com.easyshell.server.ai.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class WeComBotService implements BotChannelService {

    private final ChannelMessageRouter router;
    private final ObjectMapper objectMapper;

    private volatile String webhookUrl;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public WeComBotService(@Lazy ChannelMessageRouter router, ObjectMapper objectMapper) {
        this.router = router;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getChannelKey() {
        return "wechat-work";
    }

    @Override
    public void start() {
        webhookUrl = router.decryptConfig("ai.channel.wechat-work.webhook-url");
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.warn("WeCom webhook-url not configured, skipping start");
            return;
        }
        running.set(true);
        log.info("WeCom bot started (webhook mode)");
    }

    @Override
    public void stop() {
        running.set(false);
        log.info("WeCom bot stopped");
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean pushMessage(String targetId, String message) {
        if (!running.get()) {
            log.warn("WeCom bot not running, cannot push message");
            return false;
        }
        try {
            String truncated = truncateToBytes(message, 2048);
            WebClient.create().post()
                    .uri(webhookUrl)
                    .bodyValue(Map.of(
                            "msgtype", "text",
                            "text", Map.of("content", truncated)
                    ))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            return true;
        } catch (Exception e) {
            log.error("Failed to push WeCom webhook message: {}", e.getMessage());
            return false;
        }
    }

    private String truncateToBytes(String text, int maxBytes) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return text;
        }
        String suffix = "...";
        int suffixBytes = suffix.getBytes(StandardCharsets.UTF_8).length;
        int limit = maxBytes - suffixBytes;
        int byteCount = 0;
        int charIndex = 0;
        while (charIndex < text.length()) {
            char c = text.charAt(charIndex);
            int charBytes;
            if (c <= 0x7F) {
                charBytes = 1;
            } else if (c <= 0x7FF) {
                charBytes = 2;
            } else if (Character.isHighSurrogate(c)) {
                charBytes = 4;
            } else {
                charBytes = 3;
            }
            if (byteCount + charBytes > limit) {
                break;
            }
            byteCount += charBytes;
            charIndex++;
            if (Character.isHighSurrogate(c)) {
                charIndex++; // skip low surrogate
            }
        }
        return text.substring(0, charIndex) + suffix;
    }
}
