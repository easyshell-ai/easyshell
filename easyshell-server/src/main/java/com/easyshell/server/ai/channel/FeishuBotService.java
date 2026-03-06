package com.easyshell.server.ai.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
public class FeishuBotService implements BotChannelService {

    private final ChannelMessageRouter router;
    private final ObjectMapper objectMapper;

    // Webhook mode fields
    private volatile String webhookUrl;
    private volatile String secret;

    // Stream mode fields
    private volatile String mode; // "webhook" or "stream"
    private volatile String appId;
    private volatile String appSecret;
    private volatile String tenantAccessToken;
    private volatile long tokenExpireTime;
    private final AtomicReference<WebSocketSession> wsSession = new AtomicReference<>();
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    private volatile ScheduledExecutorService scheduler;
    private volatile ScheduledFuture<?> reconnectFuture;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ExecutorService messageExecutor;

    private static final String TOKEN_URL = "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal";
    private static final String WS_ENDPOINT_URL = "https://open.feishu.cn/open-apis/callback/ws/endpoint";
    private static final String REPLY_URL_TEMPLATE = "https://open.feishu.cn/open-apis/im/v1/messages/%s/reply";
    private static final int RECONNECT_DELAY_SECONDS = 5;
    private static final int MAX_RECONNECT_DELAY_SECONDS = 60;

    public FeishuBotService(@Lazy ChannelMessageRouter router, ObjectMapper objectMapper) {
        this.router = router;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getChannelKey() {
        return "feishu";
    }

    @Override
    public void start() {
        mode = router.getConfigValue("ai.channel.feishu.mode");
        if (mode == null || mode.isBlank()) {
            mode = "webhook";
        }

        if ("stream".equalsIgnoreCase(mode)) {
            startStreamMode();
        } else {
            startWebhookMode();
        }
    }

    private void startWebhookMode() {
        webhookUrl = router.decryptConfig("ai.channel.feishu.webhook-url");
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.warn("Feishu webhook-url not configured, skipping start");
            return;
        }
        secret = router.decryptConfig("ai.channel.feishu.secret");

        messageExecutor = new ThreadPoolExecutor(
                2, 4, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(50),
                r -> {
                    Thread t = new Thread(r, "feishu-msg-handler");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        running.set(true);
        log.info("Feishu bot started (webhook mode)");
    }

    private void startStreamMode() {
        appId = router.decryptConfig("ai.channel.feishu.app-id");
        appSecret = router.decryptConfig("ai.channel.feishu.app-secret");
        if (appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank()) {
            log.warn("Feishu Stream mode requires app-id and app-secret, skipping start");
            return;
        }
        // Also load webhook-url/secret for potential push fallback
        webhookUrl = router.decryptConfig("ai.channel.feishu.webhook-url");
        secret = router.decryptConfig("ai.channel.feishu.secret");

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "feishu-stream-scheduler");
            t.setDaemon(true);
            return t;
        });

        messageExecutor = new ThreadPoolExecutor(
                2, 4, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(50),
                r -> {
                    Thread t = new Thread(r, "feishu-stream-msg-handler");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        running.set(true);
        log.info("Feishu bot starting (stream mode)...");
        connectStream(0);
    }

    @Override
    public void stop() {
        running.set(false);
        // Cancel pending reconnect
        if (reconnectFuture != null) {
            reconnectFuture.cancel(false);
            reconnectFuture = null;
        }
        // Close WebSocket
        WebSocketSession session = wsSession.getAndSet(null);
        if (session != null && session.isOpen()) {
            try {
                session.close(CloseStatus.NORMAL);
            } catch (Exception e) {
                log.warn("Error closing Feishu Stream WebSocket: {}", e.getMessage());
            }
        }
        // Shutdown scheduler
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        // Shutdown messageExecutor
        if (messageExecutor != null) {
            messageExecutor.shutdownNow();
            messageExecutor = null;
        }
        log.info("Feishu bot stopped");
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    // ==================== Stream Mode: Token ====================

    private synchronized String getTenantAccessToken() {
        long now = System.currentTimeMillis();
        // Return cached token if still valid (with 5-minute buffer)
        if (tenantAccessToken != null && tokenExpireTime > now + 300_000) {
            return tenantAccessToken;
        }

        try {
            String responseStr = WebClient.create().post()
                    .uri(TOKEN_URL)
                    .header("Content-Type", "application/json")
                    .bodyValue(Map.of("app_id", appId, "app_secret", appSecret))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(java.time.Duration.ofSeconds(30));

            JsonNode resp = objectMapper.readTree(responseStr);
            String token = resp.path("tenant_access_token").asText(null);
            int expire = resp.path("expire").asInt(7200);

            if (token == null || token.isBlank()) {
                log.error("Feishu tenant_access_token request failed: {}", responseStr);
                return null;
            }

            tenantAccessToken = token;
            tokenExpireTime = now + (long) expire * 1000;
            log.debug("Feishu tenant_access_token refreshed, expires in {}s", expire);
            return tenantAccessToken;
        } catch (Exception e) {
            log.error("Failed to get Feishu tenant_access_token: {}", e.getMessage());
            return null;
        }
    }

    // ==================== Stream Mode: Connection ====================

    private void connectStream(int retryCount) {
        if (!running.get()) return;

        scheduler.execute(() -> {
            try {
                // Step 1: Get tenant access token
                String token = getTenantAccessToken();
                if (token == null || token.isBlank()) {
                    log.error("Feishu Stream: failed to obtain tenant_access_token");
                    scheduleReconnect(retryCount);
                    return;
                }

                // Step 2: Get WebSocket endpoint
                String responseStr = WebClient.create().post()
                        .uri(WS_ENDPOINT_URL)
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .bodyValue(Map.of())
                        .retrieve()
                        .bodyToMono(String.class)
                        .block(java.time.Duration.ofSeconds(30));

                JsonNode endpointResp = objectMapper.readTree(responseStr);
                int code = endpointResp.path("code").asInt(-1);
                if (code != 0) {
                    log.error("Feishu Stream endpoint request failed: {}", responseStr);
                    scheduleReconnect(retryCount);
                    return;
                }

                JsonNode dataNode = endpointResp.path("data");
                String wsUrl = dataNode.path("URL").asText(dataNode.path("url").asText(null));

                if (wsUrl == null || wsUrl.isBlank()) {
                    log.error("Feishu Stream endpoint returned no URL. Response: {}", responseStr);
                    scheduleReconnect(retryCount);
                    return;
                }

                // Step 3: Connect WebSocket
                log.info("Feishu Stream connecting to: {}", wsUrl);

                StandardWebSocketClient wsClient = new StandardWebSocketClient();
                WebSocketSession session = wsClient.execute(new FeishuStreamHandler(), wsUrl).get(30, TimeUnit.SECONDS);
                wsSession.set(session);
                reconnecting.set(false);
                log.info("Feishu Stream connected successfully");

            } catch (Exception e) {
                log.error("Feishu Stream connection failed: {}", e.getMessage());
                scheduleReconnect(retryCount);
            }
        });
    }

    private void scheduleReconnect(int retryCount) {
        if (!running.get()) return;
        if (reconnecting.compareAndSet(false, true)) {
            int delay = Math.min(RECONNECT_DELAY_SECONDS * (1 << Math.min(retryCount, 5)), MAX_RECONNECT_DELAY_SECONDS);
            log.info("Feishu Stream will reconnect in {} seconds (retry #{})", delay, retryCount + 1);
            if (scheduler != null && !scheduler.isShutdown()) {
                reconnectFuture = scheduler.schedule(() -> {
                    reconnecting.set(false);
                    connectStream(retryCount + 1);
                }, delay, TimeUnit.SECONDS);
            }
        }
    }

    // ==================== Stream Mode: WebSocket Handler ====================

    private class FeishuStreamHandler implements WebSocketHandler {

        @Override
        public void afterConnectionEstablished(WebSocketSession session) {
            log.debug("Feishu Stream WebSocket connection established");
        }

        @Override
        public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) {
            try {
                String payload = message.getPayload().toString();
                JsonNode msg = objectMapper.readTree(payload);

                // Feishu WS messages have a header with event_type
                JsonNode header = msg.path("header");
                String eventType = header.path("event_type").asText("");
                String messageId = header.path("message_id").asText(
                        header.path("event_id").asText(""));

                if ("im.message.receive_v1".equals(eventType)) {
                    handleStreamMessage(session, messageId, msg);
                } else {
                    log.debug("Feishu Stream ignoring event_type: {}", eventType);
                    sendAck(session, messageId);
                }
            } catch (Exception e) {
                log.error("Feishu Stream message handling error: {}", e.getMessage(), e);
            }
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) {
            log.warn("Feishu Stream transport error: {}", exception.getMessage());
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) {
            log.info("Feishu Stream WebSocket closed: {}", closeStatus);
            wsSession.compareAndSet(session, null);
            if (running.get()) {
                scheduleReconnect(0);
            }
        }

        @Override
        public boolean supportsPartialMessages() {
            return false;
        }
    }

    // ==================== Stream Mode: Message Processing ====================

    private void handleStreamMessage(WebSocketSession session, String messageId, JsonNode msg) {
        // Immediately ACK to prevent Feishu from retrying (must respond within 3s)
        sendAck(session, messageId);

        try {
            JsonNode event = msg.path("event");
            JsonNode messageNode = event.path("message");
            String msgId = messageNode.path("message_id").asText("");
            String contentStr = messageNode.path("content").asText("");

            String text = "";
            if (!contentStr.isBlank()) {
                try {
                    JsonNode contentJson = objectMapper.readTree(contentStr);
                    text = contentJson.path("text").asText("").trim();
                } catch (Exception e) {
                    log.warn("Failed to parse Feishu stream message content: {}", e.getMessage());
                }
            }

            if (text.isBlank()) {
                return;
            }

            String senderId = event.path("sender").path("sender_id").path("open_id").asText("unknown");

            String reply = router.routeMessage("feishu", text, senderId);

            if (reply != null && !reply.isBlank() && !msgId.isBlank()) {
                sendReply(msgId, reply);
            }
        } catch (Exception e) {
            log.error("Feishu Stream message processing error: {}", e.getMessage(), e);
        }
    }

    private void sendAck(WebSocketSession session, String messageId) {
        try {
            ObjectNode ack = objectMapper.createObjectNode();
            ack.put("code", 0);
            ack.put("message", "ok");
            if (messageId != null && !messageId.isBlank()) {
                ack.put("message_id", messageId);
            }

            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(ack)));
        } catch (Exception e) {
            log.warn("Feishu Stream ACK send failed: {}", e.getMessage());
        }
    }

    private void sendReply(String messageId, String content) {
        try {
            String token = getTenantAccessToken();
            if (token == null) {
                log.warn("Feishu Stream: cannot reply, no tenant_access_token");
                return;
            }

            String replyUrl = String.format(REPLY_URL_TEMPLATE, messageId);

            // Feishu reply API expects content as a JSON string
            String contentJson = objectMapper.writeValueAsString(Map.of("text", content));

            WebClient.create().post()
                    .uri(replyUrl)
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .bodyValue(Map.of(
                            "msg_type", "text",
                            "content", contentJson
                    ))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(java.time.Duration.ofSeconds(10));
            log.debug("Feishu Stream reply sent for message: {}", messageId);
        } catch (Exception e) {
            log.warn("Feishu Stream reply failed: {}", e.getMessage());
        }
    }

    // ==================== Webhook Mode: Signature Verification ====================

    public boolean verifyEventSignature(String timestamp, String nonce, String signature, String rawBody) {
        if (secret == null || secret.isBlank()) return true;
        try {
            String source = timestamp + nonce + secret + rawBody;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String calculated = bytesToHex(digest.digest(source.getBytes(StandardCharsets.UTF_8)));
            return calculated.equals(signature);
        } catch (Exception e) {
            log.warn("Feishu event signature verification failed: {}", e.getMessage());
            return false;
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // ==================== Common: Incoming Message Handling ====================

    public String handleIncomingMessage(JsonNode body) {
        if (!running.get()) {
            return "飞书机器人未启用";
        }

        String text = "";
        JsonNode eventNode = body.path("event");
        JsonNode messageNode = eventNode.path("message");
        String contentStr = messageNode.path("content").asText("");
        if (!contentStr.isBlank()) {
            try {
                JsonNode contentJson = objectMapper.readTree(contentStr);
                text = contentJson.path("text").asText("").trim();
            } catch (Exception e) {
                log.warn("Failed to parse Feishu message content: {}", e.getMessage());
            }
        }
        if (text.isBlank()) {
            return "收到空消息";
        }

        String senderId = eventNode.path("sender").path("sender_id").path("open_id").asText("unknown");

        String reply = router.routeMessage("feishu", text, senderId);
        return reply;
    }

    // ==================== Common: Push Message ====================

    @Override
    public boolean pushMessage(String targetId, String message) {
        if (!running.get()) {
            log.warn("Feishu bot not running, cannot push message");
            return false;
        }
        try {
            sendWebhookMessage(message);
            return true;
        } catch (Exception e) {
            log.error("Failed to push Feishu message: {}", e.getMessage());
            return false;
        }
    }

    public void sendWebhookMessage(String content) {
        if (webhookUrl == null || webhookUrl.isBlank()) return;

        String truncated = content.length() > 30000 ? content.substring(0, 29997) + "..." : content;

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("msg_type", "text");
            body.put("content", Map.of("text", truncated));

            if (secret != null && !secret.isBlank()) {
                String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
                String sign = calculateSign(timestamp);
                body.put("timestamp", timestamp);
                body.put("sign", sign);
            }

            WebClient.create().post()
                    .uri(webhookUrl)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        } catch (Exception e) {
            log.warn("Failed to send Feishu webhook message: {}", e.getMessage());
        }
    }

    private String calculateSign(String timestamp) {
        try {
            String stringToSign = timestamp + "\n" + secret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(stringToSign.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signData = mac.doFinal("".getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signData);
        } catch (Exception e) {
            log.error("Failed to calculate Feishu sign: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Check if currently in stream mode.
     */
    public boolean isStreamMode() {
        return "stream".equalsIgnoreCase(mode);
    }

    public ExecutorService getMessageExecutor() {
        return messageExecutor;
    }
}
