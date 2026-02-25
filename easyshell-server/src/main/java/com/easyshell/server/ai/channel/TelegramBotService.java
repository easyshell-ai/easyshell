package com.easyshell.server.ai.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Slf4j
@Component
public class TelegramBotService implements BotChannelService {

    private final ChannelMessageRouter router;
    private final ObjectMapper objectMapper;

    private volatile WebClient webClient;
    private volatile String botToken;
    private volatile Set<String> allowedChatIds;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ExecutorService pollingExecutor;
    private volatile ExecutorService messageExecutor;
    private volatile boolean stopRequested = false;
    private volatile long lastUpdateId = 0;

    public TelegramBotService(@Lazy ChannelMessageRouter router, ObjectMapper objectMapper) {
        this.router = router;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getChannelKey() {
        return "telegram";
    }

    @Override
    public void start() {
        botToken = router.decryptConfig("ai.channel.telegram.bot-token");
        if (botToken == null || botToken.isBlank()) {
            log.warn("Telegram bot-token not configured, skipping start");
            return;
        }

        String chatIdsStr = router.getConfigValue("ai.channel.telegram.allowed-chat-ids");
        allowedChatIds = parseChatIds(chatIdsStr);

        webClient = WebClient.builder()
                .baseUrl("https://api.telegram.org/bot" + botToken)
                .build();

        stopRequested = false;
        running.set(true);
        pollingExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "telegram-polling");
            t.setDaemon(true);
            return t;
        });
        // Separate thread pool for message handling so polling is never blocked
        messageExecutor = new ThreadPoolExecutor(
                2, 4, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(50),
                r -> {
                    Thread t = new Thread(r, "telegram-msg-handler");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        pollingExecutor.submit(this::pollLoop);
        log.info("Telegram bot started (long-polling mode, async message handling)");
    }

    @Override
    public void stop() {
        stopRequested = true;
        running.set(false);
        if (pollingExecutor != null) {
            pollingExecutor.shutdownNow();
            pollingExecutor = null;
        }
        if (messageExecutor != null) {
            messageExecutor.shutdownNow();
            messageExecutor = null;
        }
        log.info("Telegram bot stopped");
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void pollLoop() {
        while (!stopRequested && running.get()) {
            try {
                String response = webClient.get()
                        .uri(uri -> uri.path("/getUpdates")
                                .queryParam("offset", lastUpdateId + 1)
                                .queryParam("timeout", 30)
                                .build())
                        .retrieve()
                        .bodyToMono(String.class)
                        .block();

                if (response == null) continue;

                JsonNode root = objectMapper.readTree(response);
                if (!root.path("ok").asBoolean(false)) continue;

                JsonNode results = root.path("result");
                for (JsonNode update : results) {
                    long updateId = update.path("update_id").asLong();
                    if (updateId > lastUpdateId) {
                        lastUpdateId = updateId;
                    }
                    // Dispatch to message handler thread pool — never block polling
                    final JsonNode updateCopy = update;
                    messageExecutor.submit(() -> handleUpdate(updateCopy));
                }
            } catch (Exception e) {
                if (!stopRequested) {
                    log.warn("Telegram polling error: {}", e.getMessage());
                    sleepQuietly(5000);
                }
            }
        }
    }

    private void handleUpdate(JsonNode update) {
        JsonNode message = update.path("message");
        if (message.isMissingNode()) return;
        String chatId = message.path("chat").path("id").asText();
        String text = message.path("text").asText("");
        String fromUser = message.path("from").path("username").asText(
                message.path("from").path("first_name").asText("unknown"));
        if (text.isBlank()) return;

        log.info("Telegram received message from {} (chat {}): {}", fromUser, chatId, text.length() > 100 ? text.substring(0, 100) + "..." : text);
        if (!allowedChatIds.isEmpty() && !allowedChatIds.contains(chatId)) {
            log.warn("Telegram message from unauthorized chat: {} (user: {}). Configure allowed-chat-ids or leave empty to allow all.", chatId, fromUser);
            return;
        }

        try {
            // Send typing indicator so user knows we're processing
            sendChatAction(chatId, "typing");
            log.info("Routing Telegram message to AI for chat {}", chatId);
            String reply = router.routeMessage("telegram", text, fromUser);
            log.info("AI replied for chat {} ({} chars)", chatId, reply != null ? reply.length() : 0);
            sendMessage(chatId, reply);
        } catch (Exception e) {
            log.error("Error handling Telegram message from chat {}: {}", chatId, e.getMessage(), e);
            sendMessage(chatId, "处理消息时发生错误，请稍后重试。");
        }
    }

    @Override
    public boolean pushMessage(String chatId, String message) {
        if (!running.get()) {
            log.warn("Telegram bot not running, cannot push message to chat {}", chatId);
            return false;
        }
        try {
            sendMessage(chatId, message);
            return true;
        } catch (Exception e) {
            log.error("Failed to push message to Telegram chat {}: {}", chatId, e.getMessage());
            return false;
        }
    }

    private void sendChatAction(String chatId, String action) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("chat_id", chatId);
            body.put("action", action);
            webClient.post()
                    .uri("/sendChatAction")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        } catch (Exception e) {
            log.debug("Failed to send chat action to {}: {}", chatId, e.getMessage());
        }
    }

    private void sendMessage(String chatId, String text) {
        if (text == null || text.isBlank()) {
            log.warn("Telegram sendMessage called with empty text for chat {}", chatId);
            return;
        }

        String truncated = text.length() > 4096 ? text.substring(0, 4093) + "..." : text;

        // Try Markdown first, fall back to plain text if Telegram rejects the formatting
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("chat_id", chatId);
            body.put("text", truncated);
            body.put("parse_mode", "Markdown");

            String response = webClient.post()
                    .uri("/sendMessage")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            // Check if Telegram API returned ok=false (Markdown parse failure)
            if (response != null && !response.contains("\"ok\":true")) {
                log.warn("Telegram Markdown send failed, retrying as plain text for chat {}", chatId);
                sendPlainText(chatId, truncated);
            } else {
                log.debug("Telegram message sent to chat {} (Markdown)", chatId);
            }
        } catch (Exception e) {
            log.warn("Telegram Markdown send threw exception for chat {}, retrying as plain text: {}", chatId, e.getMessage());
            sendPlainText(chatId, truncated);
        }
    }

    private void sendPlainText(String chatId, String text) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("chat_id", chatId);
            body.put("text", text);
            webClient.post()
                    .uri("/sendMessage")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            log.debug("Telegram message sent to chat {} (plain text fallback)", chatId);
        } catch (Exception e) {
            log.error("Failed to send Telegram message (plain text) to chat {}: {}", chatId, e.getMessage());
        }
    }

    private Set<String> parseChatIds(String chatIdsStr) {
        if (chatIdsStr == null || chatIdsStr.isBlank()) return Set.of();
        return Arrays.stream(chatIdsStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}