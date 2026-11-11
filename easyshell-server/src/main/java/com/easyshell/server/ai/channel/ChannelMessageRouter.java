package com.easyshell.server.ai.channel;

import com.easyshell.server.model.entity.SystemConfig;
import com.easyshell.server.repository.SystemConfigRepository;
import com.easyshell.server.util.CryptoUtils;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

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

    private final Map<String, BotChannelService> serviceMap = new ConcurrentHashMap<>();

    public ChannelMessageRouter(@Lazy List<BotChannelService> channelServices,
                                 SystemConfigRepository systemConfigRepository,
                                 CryptoUtils cryptoUtils) {
        this.channelServices = channelServices;
        this.systemConfigRepository = systemConfigRepository;
        this.cryptoUtils = cryptoUtils;
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

    /**
     * Inbound identities are not yet authenticated and bound to local users. Never route
     * them to AI using a shared account, including for session management commands.
     */
    public String routeMessage(String channelKey, String userMessage, String externalUserId) {
        // Do not log caller-controlled identifiers, message contents, or credentials.
        log.warn("Inbound channel AI request denied: trusted user binding is unavailable");
        return "外部身份尚未授权，暂无法使用 AI；请联系管理员。 / "
                + "External identity is not authorized; AI access is unavailable. Contact an administrator.";
    }

    public String getConfigValue(String key) {
        return systemConfigRepository.findByConfigKey(key)
                .map(SystemConfig::getConfigValue)
                .orElse(null);
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
     *
     * <p><b>如实返回结果</b>（P-3）：渠道未注册 / 未启用 / 解析不出目标时只告警不抛异常，
     * 旧 {@code void} 签名让调用方只能把"没抛异常"当成"已交付"。现在把
     * {@link PushOutcome} 交回去，只有 {@code DELIVERED} 表示消息真的离开了系统。</p>
     *
     * @param channelKey 渠道标识 (telegram / discord / dingtalk / slack / feishu / wechat-work)
     * @param content 消息内容
     * @return 推送结果；{@link PushOutcome#DELIVERED} = 至少一个目标确认接收
     */
    public PushOutcome pushMessage(String channelKey, String content) {
        BotChannelService svc = serviceMap.get(channelKey);
        if (svc == null) {
            log.warn("Cannot push message: channel '{}' is not registered", channelKey);
            return PushOutcome.CHANNEL_UNKNOWN;
        }
        if (!svc.isRunning()) {
            log.warn("Cannot push message: channel '{}' not running (enabled=false or failed to start)",
                    channelKey);
            return PushOutcome.CHANNEL_NOT_RUNNING;
        }
        List<String> targets = resolvePushTargets(channelKey);
        if (targets.isEmpty()) {
            log.warn("No push targets resolved for channel '{}'", channelKey);
            return PushOutcome.NO_TARGETS;
        }
        int sent = 0;
        for (String target : targets) {
            try {
                boolean delivered = svc.pushMessage(target, content);
                if (delivered) {
                    sent++;
                    log.info("Pushed message to {} target '{}'", channelKey, target);
                } else {
                    log.warn("Failed to push message to {} target '{}'", channelKey, target);
                }
            } catch (Exception e) {
                log.error("Error pushing message to {} target '{}': {}", channelKey, target, e.getMessage());
            }
        }
        return sent > 0 ? PushOutcome.DELIVERED : PushOutcome.PUSH_FAILED;
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
     * Slack → allowed-channel-ids 配置，无配置则默认 "webhook"
     * DingTalk / Feishu / WeCom → 固定返回 "webhook"
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
            case "dingtalk" -> {
                String pushTargets = getConfigValue("ai.channel.dingtalk.push-targets");
                yield (pushTargets != null && !pushTargets.isBlank()) ? parseCsvList(pushTargets) : List.of("webhook");
            }
            case "feishu" -> {
                String pushTargets = getConfigValue("ai.channel.feishu.push-targets");
                yield (pushTargets != null && !pushTargets.isBlank()) ? parseCsvList(pushTargets) : List.of("webhook");
            }
            case "wechat-work" -> List.of("webhook");
            case "slack" -> {
                String slackChannelIds = getConfigValue("ai.channel.slack.allowed-channel-ids");
                yield (slackChannelIds != null && !slackChannelIds.isBlank()) ? parseCsvList(slackChannelIds) : List.of("webhook");
            }
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
