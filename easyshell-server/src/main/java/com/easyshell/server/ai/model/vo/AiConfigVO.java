package com.easyshell.server.ai.model.vo;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class AiConfigVO {

    private boolean enabled;
    private String defaultProvider;
    private Map<String, ProviderConfigVO> providers;
    private EmbeddingConfigVO embedding;
    private OrchestratorConfigVO orchestrator;
    private QuotaVO quota;
    private ChannelContextVO channelContext;
    private Map<String, ChannelConfigVO> channels;

    @Data
    @Builder
    public static class ProviderConfigVO {
        private String provider;
        private String apiKey;
        private String baseUrl;
        private String model;
        private Double temperature;
        private Double topP;
        private Integer maxTokens;
    }

    @Data
    @Builder
    public static class EmbeddingConfigVO {
        private String provider;
        private String model;
        private String apiKey;
        private String baseUrl;
    }

    @Data
    @Builder
    public static class OrchestratorConfigVO {
        private int maxIterations;
        private int maxToolCalls;
        private int maxConsecutiveErrors;
        private boolean sopEnabled;
        private boolean memoryEnabled;
        private String systemPromptOverride;
    }

    @Data
    @Builder
    public static class QuotaVO {
        private int dailyLimit;
        private int maxTokens;
        private int chatTimeout;
    }

    @Data
    @Builder
    public static class ChannelConfigVO {
        private String channel;
        private boolean enabled;
        private Map<String, String> settings;
    }

    @Data
    @Builder
    public static class ChannelContextVO {
        private String contextMode;
        private int sessionTimeout;
        private String defaultProvider;
        private String defaultModel;
    }
}
