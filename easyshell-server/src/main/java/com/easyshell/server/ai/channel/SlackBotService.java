package com.easyshell.server.ai.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.reactive.function.client.WebClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class SlackBotService implements BotChannelService {

    private final ChannelMessageRouter router;
    private final ObjectMapper objectMapper;

    private volatile String webhookUrl;
    private volatile String botToken;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ExecutorService messageExecutor;

    public SlackBotService(@Lazy ChannelMessageRouter router, ObjectMapper objectMapper) {
        this.router = router;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getChannelKey() {
        return "slack";
    }

    @Override
    public void start() {
        webhookUrl = router.decryptConfig("ai.channel.slack.webhook-url");
        botToken = router.decryptConfig("ai.channel.slack.bot-token");
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.warn("Slack webhook-url not configured, skipping start");
            return;
        }
        messageExecutor = new ThreadPoolExecutor(
                2, 4, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(50),
                r -> {
                    Thread t = new Thread(r, "slack-msg-handler");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        running.set(true);
        log.info("Slack bot started (webhook mode)");
    }

    @Override
    public void stop() {
        running.set(false);
        if (messageExecutor != null) {
            messageExecutor.shutdownNow();
            messageExecutor = null;
        }
        log.info("Slack bot stopped");
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean pushMessage(String targetId, String message) {
        if (!running.get()) {
            log.warn("Slack bot not running, cannot push message");
            return false;
        }
        try {
            String truncated = message.length() > 40000 ? message.substring(0, 39997) + "..." : message;
            WebClient.create().post()
                    .uri(webhookUrl)
                    .bodyValue(Map.of("text", truncated))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            return true;
        } catch (Exception e) {
            log.error("Failed to push Slack webhook message: {}", e.getMessage());
            return false;
        }
    }

    public String handleIncomingMessage(JsonNode body) {
        if (!running.get()) {
            return "Slack bot is not running";
        }

        JsonNode event = body.path("event");
        String text = event.path("text").asText("").trim();
        if (text.isBlank()) {
            return "Received empty message";
        }

        String userId = event.path("user").asText("unknown");

        String reply = router.routeMessage("slack", text, userId);
        return reply;
    }

    public void sendReplyToChannel(String channelId, String text) {
        if (botToken == null || botToken.isBlank()) {
            log.warn("Slack bot-token not configured, cannot reply to channel {}", channelId);
            return;
        }
        if (text == null || text.isBlank()) {
            log.warn("Slack sendReplyToChannel called with empty text for channel {}", channelId);
            return;
        }

        String truncated = text.length() > 40000 ? text.substring(0, 39997) + "..." : text;

        try {
            WebClient.create().post()
                    .uri("https://slack.com/api/chat.postMessage")
                    .header("Authorization", "Bearer " + botToken)
                    .bodyValue(Map.of("channel", channelId, "text", truncated))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            log.debug("Slack message sent to channel {}", channelId);
        } catch (Exception e) {
            log.error("Failed to send Slack message to channel {}: {}", channelId, e.getMessage());
        }
    }

    public boolean verifySlackSignature(String timestamp, String rawBody, String expectedSignature) {
        if (botToken == null || botToken.isBlank()) return true;
        try {
            String baseString = "v0:" + timestamp + ":" + rawBody;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(botToken.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(baseString.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            String calculated = "v0=" + hexString.toString();
            return calculated.equals(expectedSignature);
        } catch (Exception e) {
            log.warn("Slack signature verification failed: {}", e.getMessage());
            return false;
        }
    }

    public ExecutorService getMessageExecutor() {
        return messageExecutor;
    }
}
