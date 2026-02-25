package com.easyshell.server.ai.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.reactive.function.client.WebClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class DingtalkBotService implements BotChannelService {

    private final ChannelMessageRouter router;
    private final ObjectMapper objectMapper;

    private volatile String webhookUrl;
    private volatile String secret;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public DingtalkBotService(@Lazy ChannelMessageRouter router, ObjectMapper objectMapper) {
        this.router = router;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getChannelKey() {
        return "dingtalk";
    }

    @Override
    public void start() {
        webhookUrl = router.decryptConfig("ai.channel.dingtalk.webhook-url");
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.warn("DingTalk webhook-url not configured, skipping start");
            return;
        }
        secret = router.decryptConfig("ai.channel.dingtalk.secret");
        running.set(true);
        log.info("DingTalk bot started (webhook mode)");
    }

    @Override
    public void stop() {
        running.set(false);
        log.info("DingTalk bot stopped");
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    public boolean verifySignature(String timestamp, String sign) {
        if (secret == null || secret.isBlank()) return true;
        try {
            String stringToSign = timestamp + "\n" + secret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
            String calculatedSign = URLEncoder.encode(
                    Base64.getEncoder().encodeToString(signData), StandardCharsets.UTF_8);
            return calculatedSign.equals(sign);
        } catch (Exception e) {
            log.warn("DingTalk signature verification failed: {}", e.getMessage());
            return false;
        }
    }

    public String handleIncomingMessage(JsonNode body) {
        if (!running.get()) {
            return "钉钉机器人未启用";
        }

        String text = "";
        JsonNode textNode = body.path("text");
        if (!textNode.isMissingNode()) {
            text = textNode.path("content").asText("").trim();
        }
        if (text.isBlank()) {
            return "收到空消息";
        }

        String senderId = body.path("senderNick").asText(
                body.path("senderId").asText("unknown"));

        String reply = router.routeMessage("dingtalk", text, senderId);
        return reply;
    }

    @Override
    public boolean pushMessage(String targetId, String message) {
        if (!running.get()) {
            log.warn("DingTalk bot not running, cannot push message");
            return false;
        }
        try {
            sendWebhookMessage(message);
            return true;
        } catch (Exception e) {
            log.error("Failed to push DingTalk webhook message: {}", e.getMessage());
            return false;
        }
    }

    public void sendWebhookMessage(String content) {
        if (webhookUrl == null || webhookUrl.isBlank()) return;

        String url = webhookUrl;
        if (secret != null && !secret.isBlank()) {
            long timestamp = System.currentTimeMillis();
            String sign = calculateSign(timestamp);
            url = webhookUrl + "&timestamp=" + timestamp + "&sign=" + sign;
        }

        try {
            WebClient.create().post()
                    .uri(url)
                    .bodyValue(Map.of(
                            "msgtype", "text",
                            "text", Map.of("content", content)
                    ))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        } catch (Exception e) {
            log.warn("Failed to send DingTalk webhook message: {}", e.getMessage());
        }
    }

    private String calculateSign(long timestamp) {
        try {
            String stringToSign = timestamp + "\n" + secret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
            return URLEncoder.encode(
                    Base64.getEncoder().encodeToString(signData), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Failed to calculate DingTalk sign: {}", e.getMessage());
            return "";
        }
    }
}
