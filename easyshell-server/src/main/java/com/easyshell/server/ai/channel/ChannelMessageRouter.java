package com.easyshell.server.ai.channel;

import com.easyshell.server.ai.model.dto.AiChatRequest;
import com.easyshell.server.ai.model.vo.AiChatResponseVO;
import com.easyshell.server.ai.service.AiChatService;
import com.easyshell.server.model.entity.SystemConfig;
import com.easyshell.server.model.entity.User;
import com.easyshell.server.repository.SystemConfigRepository;
import com.easyshell.server.repository.UserRepository;
import com.easyshell.server.util.CryptoUtils;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Iterator;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ChannelMessageRouter {

    private final List<BotChannelService> channelServices;
    private final SystemConfigRepository systemConfigRepository;
    private final CryptoUtils cryptoUtils;
    private final AiChatService aiChatService;
    private final UserRepository userRepository;

    private final Map<String, BotChannelService> serviceMap = new ConcurrentHashMap<>();

    /** Maps "channelKey:externalUserId" -> sessionId for persistent context */
    private final Map<String, String> sessionMap = new ConcurrentHashMap<>();

    /** Maps "channelKey:externalUserId" -> last activity timestamp (epoch millis) */
    private final Map<String, Long> sessionLastActivity = new ConcurrentHashMap<>();

    private static final String CMD_NEW = "/new";

    public ChannelMessageRouter(@Lazy List<BotChannelService> channelServices,
                                 SystemConfigRepository systemConfigRepository,
                                 CryptoUtils cryptoUtils,
                                 AiChatService aiChatService,
                                 UserRepository userRepository) {
        this.channelServices = channelServices;
        this.systemConfigRepository = systemConfigRepository;
        this.cryptoUtils = cryptoUtils;
        this.aiChatService = aiChatService;
        this.userRepository = userRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        serviceMap.putAll(channelServices.stream()
                .collect(Collectors.toMap(BotChannelService::getChannelKey, Function.identity())));
        startEnabledChannels();
    }

    @PreDestroy
    public void shutdown() {
        serviceMap.values().forEach(svc -> {
            try {
                if (svc.isRunning()) {
                    svc.stop();
                    log.info("Stopped channel: {}", svc.getChannelKey());
                }
            } catch (Exception e) {
                log.warn("Error stopping channel {}: {}", svc.getChannelKey(), e.getMessage());
            }
        });
    }

    public void startEnabledChannels() {
        serviceMap.forEach((key, svc) -> {
            boolean enabled = "true".equals(getConfigValue("ai.channel." + key + ".enabled"));
            if (enabled && !svc.isRunning()) {
                try {
                    svc.start();
                    log.info("Started channel: {}", key);
                } catch (Exception e) {
                    log.error("Failed to start channel {}: {}", key, e.getMessage(), e);
                }
            } else if (!enabled && svc.isRunning()) {
                try {
                    svc.stop();
                    log.info("Stopped channel (disabled): {}", key);
                } catch (Exception e) {
                    log.warn("Error stopping channel {}: {}", key, e.getMessage());
                }
            }
        });
    }

    public void refreshChannel(String channelKey) {
        BotChannelService svc = serviceMap.get(channelKey);
        if (svc == null) return;
        boolean enabled = "true".equals(getConfigValue("ai.channel." + channelKey + ".enabled"));
        if (enabled) {
            if (svc.isRunning()) svc.stop();
            svc.start();
            log.info("Refreshed channel: {}", channelKey);
        } else if (svc.isRunning()) {
            svc.stop();
            log.info("Stopped channel (disabled): {}", channelKey);
        }
    }

    public String routeMessage(String channelKey, String userMessage, String externalUserId) {
        log.info("Routing message from channel={} user={} msgLen={}", channelKey, externalUserId, userMessage.length());


        String trimmed = userMessage.trim();
        if (CMD_NEW.equalsIgnoreCase(trimmed) || trimmed.toLowerCase().startsWith(CMD_NEW + " ")) {
            resetSession(channelKey, externalUserId);
            return "会话已重置。/ Session reset. Send a new message to start fresh.";
        }

        Long userId = resolveUserId();
        String contextMode = getConfigValueOrDefault("ai.channel.context-mode", "persistent");
        boolean isPersistent = "persistent".equalsIgnoreCase(contextMode);


        String provider = resolveProvider(channelKey);
        String model = resolveModel(channelKey);

        AiChatRequest request = new AiChatRequest();
        request.setMessage(userMessage);
        request.setProvider(provider);
        request.setModel(model);


        if (isPersistent) {
            String sessionKey = channelKey + ":" + externalUserId;
            String existingSessionId = getActiveSessionId(sessionKey);
            if (existingSessionId != null) {
                request.setSessionId(existingSessionId);
                log.debug("Reusing session {} for {}", existingSessionId, sessionKey);
            }
        }

        try {
            AiChatResponseVO response = aiChatService.chat(request, userId, "channel:" + channelKey);
            String content = response.getContent();


            if (isPersistent && response.getSessionId() != null) {
                String sessionKey = channelKey + ":" + externalUserId;
                sessionMap.put(sessionKey, response.getSessionId());
                sessionLastActivity.put(sessionKey, System.currentTimeMillis());
                log.debug("Stored session mapping: {} -> {}", sessionKey, response.getSessionId());
            }

            log.info("AI response for channel={} user={}: {} chars", channelKey, externalUserId, content != null ? content.length() : 0);
            return content;
        } catch (Exception e) {
            log.error("Channel {} message routing failed: {}", channelKey, e.getMessage(), e);
            return "处理消息时发生错误，请稍后重试。";
        }
    }

    /**
     * Get active session ID for a session key, checking timeout.
     */
    private String getActiveSessionId(String sessionKey) {
        String sessionId = sessionMap.get(sessionKey);
        if (sessionId == null) return null;

        int timeoutMinutes = getIntConfigValue("ai.channel.session-timeout", 30);
        if (timeoutMinutes <= 0) {
            return sessionId;
        }

        Long lastActivity = sessionLastActivity.get(sessionKey);
        if (lastActivity == null) return sessionId;

        long elapsed = System.currentTimeMillis() - lastActivity;
        long timeoutMs = timeoutMinutes * 60L * 1000L;
        if (elapsed > timeoutMs) {
            log.info("Session expired for {} (idle {} min, timeout {} min)", sessionKey, elapsed / 60000, timeoutMinutes);
            sessionMap.remove(sessionKey);
            sessionLastActivity.remove(sessionKey);
            return null;
        }
        return sessionId;
    }

    /**
     * Reset session for a user on a channel.
     */
    private void resetSession(String channelKey, String externalUserId) {
        String sessionKey = channelKey + ":" + externalUserId;
        sessionMap.remove(sessionKey);
        sessionLastActivity.remove(sessionKey);
        log.info("Session reset for {}", sessionKey);
    }

    /**
     * Resolve provider: per-channel override > global channel default > system default
     */
    private String resolveProvider(String channelKey) {
        String channelProvider = getConfigValue("ai.channel." + channelKey + ".provider");
        if (channelProvider != null && !channelProvider.isBlank()) {
            return channelProvider;
        }
        String globalProvider = getConfigValue("ai.channel.default-provider");
        if (globalProvider != null && !globalProvider.isBlank()) {
            return globalProvider;
        }
        return null; // will use system default
    }

    /**
     * Resolve model: per-channel override > global channel default > system default
     */
    private String resolveModel(String channelKey) {
        String channelModel = getConfigValue("ai.channel." + channelKey + ".model");
        if (channelModel != null && !channelModel.isBlank()) {
            return channelModel;
        }
        String globalModel = getConfigValue("ai.channel.default-model");
        if (globalModel != null && !globalModel.isBlank()) {
            return globalModel;
        }
        return null; // will use provider's default model
    }

    /**
     * Periodically clean up expired sessions (every 5 minutes).
     */
    @Scheduled(fixedDelay = 300_000)
    public void cleanExpiredSessions() {
        int timeoutMinutes = getIntConfigValue("ai.channel.session-timeout", 30);
        if (timeoutMinutes <= 0) return; // never expire

        long timeoutMs = timeoutMinutes * 60L * 1000L;
        long now = System.currentTimeMillis();
        int cleaned = 0;

        Iterator<Map.Entry<String, Long>> it = sessionLastActivity.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> entry = it.next();
            if (now - entry.getValue() > timeoutMs) {
                sessionMap.remove(entry.getKey());
                it.remove();
                cleaned++;
            }
        }

        if (cleaned > 0) {
            log.info("Cleaned {} expired bot sessions", cleaned);
        }
    }

    private Long resolveUserId() {
        return userRepository.findByUsername("admin")
                .map(User::getId)
                .orElse(1L);
    }

    public String getConfigValue(String key) {
        return systemConfigRepository.findByConfigKey(key)
                .map(SystemConfig::getConfigValue)
                .orElse(null);
    }

    private String getConfigValueOrDefault(String key, String defaultValue) {
        String value = getConfigValue(key);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }

    private int getIntConfigValue(String key, int defaultValue) {
        String value = getConfigValue(key);
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public String decryptConfig(String key) {
        String encrypted = getConfigValue(key);
        if (encrypted == null || encrypted.isBlank()) return null;
        try {
            return cryptoUtils.decrypt(encrypted);
        } catch (Exception e) {
            log.warn("Failed to decrypt config {}: {}", key, e.getMessage());
            return null;
        }
    }

    /**
     * 主动推送消息到指定渠道的配置目标。
     * @param channelKey 渠道标识 (telegram / discord / dingtalk)
     * @param content 消息内容
     */
    public void pushMessage(String channelKey, String content) {
        BotChannelService svc = serviceMap.get(channelKey);
        if (svc == null || !svc.isRunning()) {
            log.warn("Cannot push message: channel '{}' not available or not running", channelKey);
            return;
        }
        List<String> targets = resolvePushTargets(channelKey);
        if (targets.isEmpty()) {
            log.warn("No push targets resolved for channel '{}'", channelKey);
            return;
        }
        for (String target : targets) {
            try {
                boolean sent = svc.pushMessage(target, content);
                if (sent) {
                    log.info("Pushed message to {} target '{}'", channelKey, target);
                } else {
                    log.warn("Failed to push message to {} target '{}'", channelKey, target);
                }
            } catch (Exception e) {
                log.error("Error pushing message to {} target '{}': {}", channelKey, target, e.getMessage());
            }
        }
    }

    /**
     * 异步推送消息到多个渠道。
     * @param channelKeys 渠道标识列表
     * @param content 消息内容
     */
    @Async
    public void pushToChannelsAsync(List<String> channelKeys, String content) {
        for (String channelKey : channelKeys) {
            pushMessage(channelKey, content);
        }
    }

    /**
     * 根据渠道配置解析推送目标。
     * Telegram → allowed-chat-ids 配置
     * Discord → allowed-channel-ids 配置
     * DingTalk → 固定返回 "webhook"
     */
    private List<String> resolvePushTargets(String channelKey) {
        return switch (channelKey) {
            case "telegram" -> {
                String chatIds = getConfigValue("ai.channel.telegram.allowed-chat-ids");
                yield parseCsvList(chatIds);
            }
            case "discord" -> {
                String channelIds = getConfigValue("ai.channel.discord.allowed-channel-ids");
                yield parseCsvList(channelIds);
            }
            case "dingtalk" -> List.of("webhook");
            default -> {
                log.warn("Unknown channel key for push targets: {}", channelKey);
                yield List.of();
            }
        };
    }

    private List<String> parseCsvList(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
