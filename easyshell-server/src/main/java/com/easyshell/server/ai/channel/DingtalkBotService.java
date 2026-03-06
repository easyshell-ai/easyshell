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
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
public class DingtalkBotService implements BotChannelService {

    private final ChannelMessageRouter router;
    private final ObjectMapper objectMapper;

    // Webhook mode fields
    private volatile String webhookUrl;
    private volatile String secret;

    // Stream mode fields
    private volatile String clientId;
    private volatile String clientSecret;
    private volatile String mode; // "webhook" or "stream"
    private final AtomicReference<WebSocketSession> wsSession = new AtomicReference<>();
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    private volatile ScheduledExecutorService scheduler;
    private volatile ScheduledFuture<?> reconnectFuture;

    private final AtomicBoolean running = new AtomicBoolean(false);

    private static final String STREAM_REGISTER_URL = "https://api.dingtalk.com/v1.0/gateway/connections/open";
    private static final String BOT_MESSAGE_TOPIC = "/v1.0/im/bot/messages/get";
    private static final int RECONNECT_DELAY_SECONDS = 5;
    private static final int MAX_RECONNECT_DELAY_SECONDS = 60;

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
        mode = router.getConfigValue("ai.channel.dingtalk.mode");
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
        webhookUrl = router.decryptConfig("ai.channel.dingtalk.webhook-url");
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.warn("DingTalk webhook-url not configured, skipping start");
            return;
        }
        secret = router.decryptConfig("ai.channel.dingtalk.secret");
        running.set(true);
        log.info("DingTalk bot started (webhook mode)");
    }

    private void startStreamMode() {
        clientId = router.decryptConfig("ai.channel.dingtalk.client-id");
        clientSecret = router.decryptConfig("ai.channel.dingtalk.client-secret");
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            log.warn("DingTalk Stream mode requires client-id and client-secret, skipping start");
            return;
        }
        // Also load webhook-url/secret for potential push fallback
        webhookUrl = router.decryptConfig("ai.channel.dingtalk.webhook-url");
        secret = router.decryptConfig("ai.channel.dingtalk.secret");

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dingtalk-stream-scheduler");
            t.setDaemon(true);
            return t;
        });
        running.set(true);
        log.info("DingTalk bot starting (stream mode)...");
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
                log.warn("Error closing DingTalk Stream WebSocket: {}", e.getMessage());
            }
        }
        // Shutdown scheduler
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        log.info("DingTalk bot stopped");
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    // ==================== Stream Mode: Connection ====================

    private void connectStream(int retryCount) {
        if (!running.get()) return;

        scheduler.execute(() -> {
            try {
                // Step 1: Get WebSocket ticket
                Map<String, Object> registerBody = Map.of(
                        "clientId", clientId,
                        "clientSecret", clientSecret,
                        "subscriptions", List.of(
                                Map.of("type", "CALLBACK", "topic", BOT_MESSAGE_TOPIC)
                        ),
                        "ua", "easyshell-server/1.0",
                        "localIp", "127.0.0.1"
                );

                String responseStr = WebClient.create().post()
                        .uri(STREAM_REGISTER_URL)
                        .header("Content-Type", "application/json")
                        .bodyValue(registerBody)
                        .retrieve()
                        .bodyToMono(String.class)
                        .block(java.time.Duration.ofSeconds(30));

                JsonNode registerResp = objectMapper.readTree(responseStr);
                String endpoint = registerResp.path("endpoint").asText(null);
                String ticket = registerResp.path("ticket").asText(null);

                if (endpoint == null || ticket == null || endpoint.isBlank() || ticket.isBlank()) {
                    log.error("DingTalk Stream registration failed: endpoint or ticket missing. Response: {}", responseStr);
                    scheduleReconnect(retryCount);
                    return;
                }

                // Step 2: Connect WebSocket
                String wsUrl = endpoint + "?ticket=" + URLEncoder.encode(ticket, StandardCharsets.UTF_8);
                log.info("DingTalk Stream connecting to: {}", endpoint);

                StandardWebSocketClient wsClient = new StandardWebSocketClient();
                WebSocketSession session = wsClient.execute(new DingtalkStreamHandler(), wsUrl).get(30, TimeUnit.SECONDS);
                wsSession.set(session);
                reconnecting.set(false);
                log.info("DingTalk Stream connected successfully");

            } catch (Exception e) {
                log.error("DingTalk Stream connection failed: {}", e.getMessage());
                scheduleReconnect(retryCount);
            }
        });
    }

    private void scheduleReconnect(int retryCount) {
        if (!running.get()) return;
        if (reconnecting.compareAndSet(false, true)) {
            int delay = Math.min(RECONNECT_DELAY_SECONDS * (1 << Math.min(retryCount, 5)), MAX_RECONNECT_DELAY_SECONDS);
            log.info("DingTalk Stream will reconnect in {} seconds (retry #{})", delay, retryCount + 1);
            if (scheduler != null && !scheduler.isShutdown()) {
                reconnectFuture = scheduler.schedule(() -> {
                    reconnecting.set(false);
                    connectStream(retryCount + 1);
                }, delay, TimeUnit.SECONDS);
            }
        }
    }

    // ==================== Stream Mode: WebSocket Handler ====================

    private class DingtalkStreamHandler implements WebSocketHandler {

        @Override
        public void afterConnectionEstablished(WebSocketSession session) {
            log.debug("DingTalk Stream WebSocket connection established");
        }

        @Override
        public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) {
            try {
                String payload = message.getPayload().toString();
                JsonNode msg = objectMapper.readTree(payload);
                String type = msg.path("type").asText("");
                String topic = msg.path("headers").path("topic").asText("");
                String messageId = msg.path("headers").path("messageId").asText("");

                switch (type) {
                    case "SYSTEM" -> handleSystemMessage(session, topic, messageId, msg);
                    case "CALLBACK" -> handleCallbackMessage(session, topic, messageId, msg);
                    default -> {
                        log.debug("DingTalk Stream unknown message type: {}", type);
                        sendAck(session, messageId);
                    }
                }
            } catch (Exception e) {
                log.error("DingTalk Stream message handling error: {}", e.getMessage(), e);
            }
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) {
            log.warn("DingTalk Stream transport error: {}", exception.getMessage());
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) {
            log.info("DingTalk Stream WebSocket closed: {}", closeStatus);
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

    private void handleSystemMessage(WebSocketSession session, String topic, String messageId, JsonNode msg) {
        switch (topic) {
            case "ping" -> {
                try {
                    String data = msg.path("data").asText("{}");
                    JsonNode dataNode = objectMapper.readTree(data);
                    String opaque = dataNode.path("opaque").asText("");

                    ObjectNode response = objectMapper.createObjectNode();
                    response.put("code", 200);
                    response.put("message", "OK");
                    ObjectNode headers = objectMapper.createObjectNode();
                    headers.put("messageId", messageId);
                    headers.put("contentType", "application/json");
                    response.set("headers", headers);
                    response.put("data", objectMapper.writeValueAsString(Map.of("opaque", opaque)));

                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(response)));
                    log.trace("DingTalk Stream pong sent");
                } catch (Exception e) {
                    log.warn("DingTalk Stream ping response failed: {}", e.getMessage());
                }
            }
            case "disconnect" -> {
                log.info("DingTalk Stream received disconnect notice, will reconnect...");
                // DingTalk waits 10s before closing TCP; we preemptively reconnect
                if (running.get()) {
                    scheduleReconnect(0);
                }
            }
            default -> {
                log.debug("DingTalk Stream system topic: {}", topic);
                sendAck(session, messageId);
            }
        }
    }

    private void handleCallbackMessage(WebSocketSession session, String topic, String messageId, JsonNode msg) {
        // Immediately ACK to prevent DingTalk from retrying
        sendAck(session, messageId);

        if (!BOT_MESSAGE_TOPIC.equals(topic)) {
            log.debug("DingTalk Stream ignoring callback topic: {}", topic);
            return;
        }

        try {
            // Parse the stringified data field
            String dataStr = msg.path("data").asText("{}");
            JsonNode body = objectMapper.readTree(dataStr);

            // Extract sessionWebhook for reply
            String sessionWebhook = body.path("sessionWebhook").asText(null);

            // Reuse the existing handleIncomingMessage logic
            String reply = handleIncomingMessage(body);

            // Reply via sessionWebhook
            if (sessionWebhook != null && !sessionWebhook.isBlank() && reply != null && !reply.isBlank()) {
                sendSessionWebhookReply(sessionWebhook, reply);
            }
        } catch (Exception e) {
            log.error("DingTalk Stream callback processing error: {}", e.getMessage(), e);
        }
    }

    private void sendAck(WebSocketSession session, String messageId) {
        try {
            ObjectNode ack = objectMapper.createObjectNode();
            ack.put("code", 200);
            ack.put("message", "OK");
            ObjectNode headers = objectMapper.createObjectNode();
            headers.put("messageId", messageId);
            headers.put("contentType", "application/json");
            ack.set("headers", headers);
            ack.put("data", "");

            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(ack)));
        } catch (Exception e) {
            log.warn("DingTalk Stream ACK send failed: {}", e.getMessage());
        }
    }

    private void sendSessionWebhookReply(String sessionWebhook, String content) {
        try {
            WebClient.create().post()
                    .uri(sessionWebhook)
                    .header("Content-Type", "application/json")
                    .bodyValue(Map.of(
                            "msgtype", "text",
                            "text", Map.of("content", content)
                    ))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(java.time.Duration.ofSeconds(10));
            log.debug("DingTalk Stream reply sent via sessionWebhook");
        } catch (Exception e) {
            log.warn("DingTalk Stream reply via sessionWebhook failed: {}", e.getMessage());
        }
    }

    // ==================== Webhook Mode: Signature Verification ====================

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

    // ==================== Common: Incoming Message Handling ====================

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

    // ==================== Common: Push Message ====================

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
            log.error("Failed to push DingTalk message: {}", e.getMessage());
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

    /**
     * Check if currently in stream mode.
     */
    public boolean isStreamMode() {
        return "stream".equalsIgnoreCase(mode);
    }
}
