package com.easyshell.server.ai.config;

import com.easyshell.server.ai.service.ChatModelFactory;
import com.easyshell.server.ai.channel.ChannelMessageRouter;
import com.easyshell.server.ai.service.EmbeddingModelFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiConfigRefreshService {

    private final ChatModelFactory chatModelFactory;
    private final EmbeddingModelFactory embeddingModelFactory;
    private final ChannelMessageRouter channelMessageRouter;

    private static final Set<String> AI_CONFIG_PREFIXES = Set.of(
            "ai.openai.",
            "ai.anthropic.",
            "ai.ollama.",
            "ai.gemini.",
            "ai.github-copilot.",
            "ai.default.",
            "ai.memory.embedding"
    );

    private static final String CHANNEL_CONFIG_PREFIX = "ai.channel.";

    public void onConfigChanged(String configKey) {
        if (configKey == null) return;
        
        boolean isAiConfig = AI_CONFIG_PREFIXES.stream()
                .anyMatch(configKey::startsWith);
        
        if (isAiConfig) {
            log.info("AI config changed: {}, invalidating model caches", configKey);
            invalidateAllCaches();
        }

        // Refresh bot channel if channel config changed
        if (configKey.startsWith(CHANNEL_CONFIG_PREFIX)) {
            String rest = configKey.substring(CHANNEL_CONFIG_PREFIX.length());
            String channelKey = rest.contains(".") ? rest.substring(0, rest.indexOf('.')) : rest;
            log.info("Channel config changed: {}, refreshing channel: {}", configKey, channelKey);
            try {
                channelMessageRouter.refreshChannel(channelKey);
            } catch (Exception e) {
                log.warn("Failed to refresh channel {}: {}", channelKey, e.getMessage());
            }
        }
    }

    public void invalidateAllCaches() {
        chatModelFactory.invalidateCache();
        embeddingModelFactory.invalidateCache();
        log.info("All AI model caches invalidated");
    }
}
