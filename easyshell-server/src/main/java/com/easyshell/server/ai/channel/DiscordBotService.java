package com.easyshell.server.ai.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Arrays;
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
public class DiscordBotService implements BotChannelService {

    private final ChannelMessageRouter router;
    private final ObjectMapper objectMapper;

    private volatile WebClient webClient;
    private volatile String botToken;
    private volatile String guildId;
    private volatile Set<String> allowedChannelIds;
    private volatile String botUserId;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ExecutorService pollingExecutor;
    private volatile ExecutorService messageExecutor;
    private volatile boolean stopRequested = false;
    private volatile String lastMessageId;

    private static final String DISCORD_API = "https://discord.com/api/v10";

    public DiscordBotService(@Lazy ChannelMessageRouter router, ObjectMapper objectMapper) {
        this.router = router;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getChannelKey() {
        return "discord";
    }

    @Override
    public void start() {
        botToken = router.decryptConfig("ai.channel.discord.bot-token");
        if (botToken == null || botToken.isBlank()) {
            log.warn("Discord bot-token not configured, skipping start");
            return;
        }

        guildId = router.getConfigValue("ai.channel.discord.guild-id");
        String channelIdsStr = router.getConfigValue("ai.channel.discord.allowed-channel-ids");
        allowedChannelIds = parseIds(channelIdsStr);

        webClient = WebClient.builder()
                .baseUrl(DISCORD_API)
                .defaultHeader("Authorization", "Bot " + botToken)
                .defaultHeader("Content-Type", "application/json")
                .build();

        botUserId = fetchBotUserId();

        stopRequested = false;
        running.set(true);
        pollingExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "discord-polling");
            t.setDaemon(true);
            return t;
        });
        // Separate thread pool for message handling so polling is never blocked
        messageExecutor = new ThreadPoolExecutor(
                2, 4, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(50),
                r -> {
                    Thread t = new Thread(r, "discord-msg-handler");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        pollingExecutor.submit(this::pollLoop);
        log.info("Discord bot started (REST polling mode, guild={}, async message handling)", guildId);
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
        log.info("Discord bot stopped");
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private String fetchBotUserId() {
        try {
            String response = webClient.get()
                    .uri("/users/@me")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            if (response != null) {
                JsonNode node = objectMapper.readTree(response);
                return node.path("id").asText(null);
            }
        } catch (Exception e) {
            log.warn("Failed to fetch Discord bot user info: {}", e.getMessage());
        }
        return null;
    }

    private void pollLoop() {
        for (String channelId : allowedChannelIds) {
            initLastMessageId(channelId);
        }

        while (!stopRequested && running.get()) {
            try {
                for (String channelId : allowedChannelIds) {
                    pollChannel(channelId);
                }
                sleepQuietly(2000);
            } catch (Exception e) {
                if (!stopRequested) {
                    log.warn("Discord polling error: {}", e.getMessage());
                    sleepQuietly(5000);
                }
            }
        }
    }

    private void initLastMessageId(String channelId) {
        try {
            String response = webClient.get()
                    .uri("/channels/{channelId}/messages?limit=1", channelId)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (response != null) {
                JsonNode messages = objectMapper.readTree(response);
                if (messages.isArray() && !messages.isEmpty()) {
                    lastMessageId = messages.get(0).path("id").asText();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to init last message ID for Discord channel {}: {}", channelId, e.getMessage());
        }
    }

    private void pollChannel(String channelId) {
        try {
            String url = lastMessageId != null
                    ? "/channels/" + channelId + "/messages?after=" + lastMessageId + "&limit=50"
                    : "/channels/" + channelId + "/messages?limit=1";

            String response = webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (response == null) return;

            JsonNode messages = objectMapper.readTree(response);
            if (!messages.isArray()) return;

            for (int i = messages.size() - 1; i >= 0; i--) {
                JsonNode msg = messages.get(i);
                String msgId = msg.path("id").asText();
                String authorId = msg.path("author").path("id").asText();
                boolean isBot = msg.path("author").path("bot").asBoolean(false);

                if (isBot || (botUserId != null && botUserId.equals(authorId))) continue;

                String content = msg.path("content").asText("");
                String username = msg.path("author").path("username").asText("unknown");

                if (content.isBlank()) continue;

                if (lastMessageId == null || msgId.compareTo(lastMessageId) > 0) {
                    lastMessageId = msgId;
                }

                // Dispatch to message handler thread pool — never block polling
                final String cId = channelId;
                final String c = content;
                final String u = username;
                messageExecutor.submit(() -> handleDiscordMessage(cId, c, u));
            }
        } catch (Exception e) {
            log.warn("Error polling Discord channel {}: {}", channelId, e.getMessage());
        }
    }

    private void handleDiscordMessage(String channelId, String content, String username) {
        try {
            String reply = router.routeMessage("discord", content, username);
            sendMessage(channelId, reply);
        } catch (Exception e) {
            log.error("Error handling Discord message: {}", e.getMessage(), e);
            sendMessage(channelId, "处理消息时发生错误，请稍后重试。");
        }
    }

    @Override
    public boolean pushMessage(String channelId, String message) {
        if (!running.get()) {
            log.warn("Discord bot not running, cannot push message to channel {}", channelId);
            return false;
        }
        try {
            sendMessage(channelId, message);
            return true;
        } catch (Exception e) {
            log.error("Failed to push message to Discord channel {}: {}", channelId, e.getMessage());
            return false;
        }
    }

    private void sendMessage(String channelId, String text) {
        if (text == null || text.isBlank()) return;

        String truncated = text.length() > 2000 ? text.substring(0, 1997) + "..." : text;

        try {
            webClient.post()
                    .uri("/channels/{channelId}/messages", channelId)
                    .bodyValue(java.util.Map.of("content", truncated))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        } catch (Exception e) {
            log.warn("Failed to send Discord message to channel {}: {}", channelId, e.getMessage());
        }
    }

    private Set<String> parseIds(String idsStr) {
        if (idsStr == null || idsStr.isBlank()) return Set.of();
        return Arrays.stream(idsStr.split(","))
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