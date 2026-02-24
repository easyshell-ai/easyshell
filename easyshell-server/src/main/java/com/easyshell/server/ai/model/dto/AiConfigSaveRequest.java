package com.easyshell.server.ai.model.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
public class AiConfigSaveRequest {

    @NotNull(message = "enabled 不能为空")
    private Boolean enabled;

    private String defaultProvider;

    private Map<String, ProviderConfig> providers;

    private EmbeddingConfig embedding;

    private OrchestratorConfig orchestrator;

    private QuotaConfig quota;

    private ChannelContextConfig channelContext;
    private Map<String, ChannelConfig> channels;

    @Data
    public static class ProviderConfig {
        private String apiKey;
        private String baseUrl;
        private String model;
        private Double temperature;
        private Double topP;
        private Integer maxTokens;
    }

    @Data
    public static class EmbeddingConfig {
        private String provider;
        private String model;
        private String apiKey;
        private String baseUrl;
    }

    @Data
    public static class OrchestratorConfig {
        private Integer maxIterations;
        private Integer maxToolCalls;
        private Integer maxConsecutiveErrors;
        private Boolean sopEnabled;
        private Boolean memoryEnabled;
        private String systemPromptOverride;
    }

    @Data
    public static class QuotaConfig {
        private Integer dailyLimit;
        private Integer maxTokens;
        private Integer chatTimeout;
    }

    @Data
    public static class ChannelConfig {
        private Boolean enabled;
        private Map<String, String> settings;
    }

    @Data
    public static class ChannelContextConfig {
        private String contextMode;
        private Integer sessionTimeout;
        private String defaultProvider;
        private String defaultModel;
    }
}
