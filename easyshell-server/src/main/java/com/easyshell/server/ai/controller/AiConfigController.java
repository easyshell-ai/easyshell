package com.easyshell.server.ai.controller;

import com.easyshell.server.ai.config.AiConfigRefreshService;
import com.easyshell.server.ai.channel.ChannelMessageRouter;
import com.easyshell.server.ai.model.dto.AiConfigSaveRequest;
import com.easyshell.server.ai.model.dto.AiTestRequest;
import com.easyshell.server.ai.model.vo.AiConfigVO;
import com.easyshell.server.ai.model.vo.AiTestResult;
import com.easyshell.server.ai.security.AiQuotaService;
import com.easyshell.server.ai.service.ChatModelFactory;
import com.easyshell.server.ai.service.CopilotAuthService;
import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.common.result.R;
import com.easyshell.server.model.entity.SystemConfig;
import com.easyshell.server.repository.SystemConfigRepository;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.util.CryptoUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Lazy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@RestController
@RequestMapping("/api/v1/ai/config")
public class AiConfigController {

    private final SystemConfigRepository systemConfigRepository;
    private final CryptoUtils cryptoUtils;
    private final ChatModelFactory chatModelFactory;
    private final AuditLogService auditLogService;
    private final AiQuotaService quotaService;
    private final AiConfigRefreshService aiConfigRefreshService;
    private final ObjectMapper objectMapper;
    private final ChannelMessageRouter channelMessageRouter;
    private final CopilotAuthService copilotAuthService;

    public AiConfigController(SystemConfigRepository systemConfigRepository,
                               CryptoUtils cryptoUtils,
                               ChatModelFactory chatModelFactory,
                               AuditLogService auditLogService,
                               AiQuotaService quotaService,
                               AiConfigRefreshService aiConfigRefreshService,
                               ObjectMapper objectMapper,
                               ChannelMessageRouter channelMessageRouter,
                               @Lazy CopilotAuthService copilotAuthService) {
        this.systemConfigRepository = systemConfigRepository;
        this.cryptoUtils = cryptoUtils;
        this.chatModelFactory = chatModelFactory;
        this.auditLogService = auditLogService;
        this.quotaService = quotaService;
        this.aiConfigRefreshService = aiConfigRefreshService;
        this.objectMapper = objectMapper;
        this.channelMessageRouter = channelMessageRouter;
        this.copilotAuthService = copilotAuthService;
    }

    private static final String MASKED_KEY_PATTERN = "***";
    private static final Set<String> CHANNEL_SENSITIVE_KEYS = Set.of(
            "bot-token", "webhook-url", "secret"
    );
    private static final Map<String, List<String>> CHANNEL_SETTINGS_KEYS = Map.of(
            "telegram", List.of("bot-token", "allowed-chat-ids"),
            "discord", List.of("bot-token", "guild-id", "allowed-channel-ids"),
            "dingtalk", List.of("webhook-url", "secret"),
            "feishu", List.of("webhook-url", "secret"),
            "slack", List.of("webhook-url", "bot-token"),
            "wechat-work", List.of("webhook-url")
    );

    @GetMapping
    public R<AiConfigVO> getConfig() {
        Map<String, AiConfigVO.ProviderConfigVO> providers = new LinkedHashMap<>();

        providers.put("openai", buildProviderConfig("openai", true));
        providers.put("anthropic", buildProviderConfig("anthropic", true));
        providers.put("gemini", buildProviderConfig("gemini", true));
        providers.put("ollama", buildProviderConfig("ollama", false));
        providers.put("github-copilot", buildProviderConfig("github-copilot", false));

        AiConfigVO config = AiConfigVO.builder()
                .enabled("true".equals(getConfigValue("ai.enabled")))
                .defaultProvider(getConfigValue("ai.default.provider"))
                .providers(providers)
                .embedding(buildEmbeddingConfig())
                .orchestrator(buildOrchestratorConfig())
                .quota(AiConfigVO.QuotaVO.builder()
                        .dailyLimit(getIntConfigValue("ai.quota.daily-limit", 100))
                        .maxTokens(getIntConfigValue("ai.quota.max-tokens", 4096))
                        .chatTimeout(getIntConfigValue("ai.chat.timeout", 120))
                        .build())
                .channelContext(buildChannelContextConfig())
                .channels(buildChannelConfigs())
                .build();

        return R.ok(config);
    }

    @PutMapping
    public R<Void> saveConfig(@Valid @RequestBody AiConfigSaveRequest request,
                              Authentication auth, HttpServletRequest httpRequest) {
        Long userId = (Long) auth.getPrincipal();

        saveConfigValue("ai.enabled", String.valueOf(request.getEnabled()));
        if (request.getDefaultProvider() != null) {
            saveConfigValue("ai.default.provider", request.getDefaultProvider());
        }

        if (request.getProviders() != null) {
            request.getProviders().forEach((provider, config) -> {
                String prefix = "ai." + provider;

                if (config.getApiKey() != null && !config.getApiKey().contains(MASKED_KEY_PATTERN)) {
                    saveConfigValue(prefix + ".api-key", cryptoUtils.encrypt(config.getApiKey()));
                }
                if (config.getBaseUrl() != null) {
                    saveConfigValue(prefix + ".base-url", config.getBaseUrl());
                }
                if (config.getModel() != null) {
                    saveConfigValue(prefix + ".model", config.getModel());
                }
                if (config.getTemperature() != null) {
                    saveConfigValue(prefix + ".temperature", String.valueOf(config.getTemperature()));
                }
                if (config.getTopP() != null) {
                    saveConfigValue(prefix + ".top-p", String.valueOf(config.getTopP()));
                }
                if (config.getMaxTokens() != null) {
                    saveConfigValue(prefix + ".max-tokens", String.valueOf(config.getMaxTokens()));
                }
            });
        }

        if (request.getEmbedding() != null) {
            var emb = request.getEmbedding();
            if (emb.getProvider() != null) {
                saveConfigValue("ai.memory.embedding-provider", emb.getProvider());
            }
            if (emb.getModel() != null) {
                saveConfigValue("ai.memory.embedding-model", emb.getModel());
            }
            if (emb.getApiKey() != null && !emb.getApiKey().contains(MASKED_KEY_PATTERN)) {
                saveConfigValue("ai.memory.embedding-api-key", cryptoUtils.encrypt(emb.getApiKey()));
            }
            if (emb.getBaseUrl() != null) {
                saveConfigValue("ai.memory.embedding-base-url", emb.getBaseUrl());
            }
        }

        if (request.getOrchestrator() != null) {
            var orch = request.getOrchestrator();
            if (orch.getMaxIterations() != null) {
                saveConfigValue("ai.orchestrator.max-iterations", String.valueOf(orch.getMaxIterations()));
            }
            if (orch.getMaxToolCalls() != null) {
                saveConfigValue("ai.orchestrator.max-tool-calls", String.valueOf(orch.getMaxToolCalls()));
            }
            if (orch.getMaxConsecutiveErrors() != null) {
                saveConfigValue("ai.orchestrator.max-consecutive-errors", String.valueOf(orch.getMaxConsecutiveErrors()));
            }
            if (orch.getSopEnabled() != null) {
                saveConfigValue("ai.memory.sop-enabled", String.valueOf(orch.getSopEnabled()));
            }
            if (orch.getMemoryEnabled() != null) {
                saveConfigValue("ai.memory.enabled", String.valueOf(orch.getMemoryEnabled()));
            }
            if (orch.getSystemPromptOverride() != null) {
                saveConfigValue("ai.orchestrator.system-prompt-override", orch.getSystemPromptOverride());
            }
        }

        if (request.getQuota() != null) {
            if (request.getQuota().getDailyLimit() != null) {
                saveConfigValue("ai.quota.daily-limit", String.valueOf(request.getQuota().getDailyLimit()));
            }
            if (request.getQuota().getMaxTokens() != null) {
                saveConfigValue("ai.quota.max-tokens", String.valueOf(request.getQuota().getMaxTokens()));
            }
            if (request.getQuota().getChatTimeout() != null) {
                saveConfigValue("ai.chat.timeout", String.valueOf(request.getQuota().getChatTimeout()));
            }
        }

        if (request.getChannels() != null) {
            request.getChannels().forEach((channel, channelConfig) -> {
                String prefix = "ai.channel." + channel;
                if (channelConfig.getEnabled() != null) {
                    saveConfigValue(prefix + ".enabled", String.valueOf(channelConfig.getEnabled()));
                }
                if (channelConfig.getSettings() != null) {
                    channelConfig.getSettings().forEach((settingKey, settingValue) -> {
                        String configKey = prefix + "." + settingKey;
                        if (CHANNEL_SENSITIVE_KEYS.contains(settingKey)
                                && settingValue != null && !settingValue.contains(MASKED_KEY_PATTERN)) {
                            saveConfigValue(configKey, cryptoUtils.encrypt(settingValue));
                        } else if (settingValue != null && !settingValue.contains(MASKED_KEY_PATTERN)) {
                            saveConfigValue(configKey, settingValue);
                        }
                    });
                }
            });
            // Refresh bot channels immediately after config change
            request.getChannels().forEach((channel, channelConfig) -> {
                try {
                    channelMessageRouter.refreshChannel(channel);
                } catch (Exception e) {
                    log.warn("Failed to refresh channel {}: {}", channel, e.getMessage());
                }
            });
        }

        // Save channel context settings
        if (request.getChannelContext() != null) {
            var ctx = request.getChannelContext();
            if (ctx.getContextMode() != null) {
                saveConfigValue("ai.channel.context-mode", ctx.getContextMode());
            }
            if (ctx.getSessionTimeout() != null) {
                saveConfigValue("ai.channel.session-timeout", String.valueOf(ctx.getSessionTimeout()));
            }
            if (ctx.getDefaultProvider() != null) {
                saveConfigValue("ai.channel.default-provider", ctx.getDefaultProvider());
            }
            if (ctx.getDefaultModel() != null) {
                saveConfigValue("ai.channel.default-model", ctx.getDefaultModel());
            }
        }

        aiConfigRefreshService.invalidateAllCaches();

        auditLogService.log(userId, auth.getName(), "UPDATE_AI_CONFIG", "ai_config",
                null, "AI 配置更新", httpRequest.getRemoteAddr(), "success");

        return R.ok();
    }

    @PostMapping("/test")
    public R<AiTestResult> testConnection(@Valid @RequestBody AiTestRequest request) {
        long startTime = System.currentTimeMillis();
        try {
            ChatModel chatModel = createTestModel(request);
            ChatResponse response = chatModel.call(new Prompt("Say hello in one short sentence."));
            long elapsed = System.currentTimeMillis() - startTime;

            String content = response.getResult().getOutput().getText();

            return R.ok(AiTestResult.builder()
                    .success(true)
                    .message("连接成功")
                    .responseTimeMs(elapsed)
                    .modelInfo(content)
                    .build());
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.warn("AI connection test failed for provider {}: {}", request.getProvider(), e.getMessage());
            return R.ok(AiTestResult.builder()
                    .success(false)
                    .message("连接失败: " + e.getMessage())
                    .responseTimeMs(elapsed)
                    .modelInfo(null)
                    .build());
        }
    }

    @GetMapping("/models")
    public R<List<Map<String, Object>>> listModels(@RequestParam String provider) {
        try {
            List<Map<String, Object>> models = fetchModelsForProvider(provider);
            return R.ok(models);
        } catch (BusinessException e) {
            return R.fail(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.warn("Failed to list models for provider {}: {}", provider, e.getMessage());
            return R.fail(502, "获取模型列表失败: " + e.getMessage());
        }
    }

    private List<Map<String, Object>> fetchModelsForProvider(String provider) throws Exception {
        return switch (provider) {
            case "github-copilot" -> copilotAuthService.listModels();
            case "ollama" -> fetchOllamaModels();
            case "openai" -> fetchOpenAiCompatibleModels("ai.openai", "https://api.openai.com");
            case "gemini" -> fetchOpenAiCompatibleModels("ai.gemini",
                    "https://generativelanguage.googleapis.com/v1beta/openai/");
            case "anthropic" -> fetchAnthropicModels();
            default -> fetchOpenAiCompatibleModels("ai." + provider, null);
        };
    }

    private List<Map<String, Object>> fetchOllamaModels() throws Exception {
        String baseUrl = getConfigValue("ai.ollama.base-url");
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = "http://localhost:11434";
        String url = baseUrl.endsWith("/") ? baseUrl + "api/tags" : baseUrl + "/api/tags";

        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10)).build();
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .header("Accept", "application/json")
                .GET()
                .timeout(java.time.Duration.ofSeconds(15))
                .build();
        java.net.http.HttpResponse<String> response = client.send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new BusinessException(502, "Ollama 返回错误: HTTP " + response.statusCode());
        }

        var json = objectMapper.readTree(response.body());
        List<Map<String, Object>> models = new java.util.ArrayList<>();
        var modelsNode = json.has("models") ? json.get("models") : json;
        if (modelsNode.isArray()) {
            for (var node : modelsNode) {
                Map<String, Object> model = new LinkedHashMap<>();
                model.put("id", node.path("name").asText());
                model.put("name", node.path("name").asText());
                models.add(model);
            }
        }
        return models;
    }

    private List<Map<String, Object>> fetchOpenAiCompatibleModels(String configPrefix,
            String defaultBaseUrl) throws Exception {
        String baseUrl = getConfigValue(configPrefix + ".base-url");
        if ((baseUrl == null || baseUrl.isBlank()) && defaultBaseUrl != null) baseUrl = defaultBaseUrl;
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new BusinessException(400, "未配置 base-url，无法获取模型列表");
        }

        String apiKeyEncrypted = getConfigValue(configPrefix + ".api-key");
        String apiKey = "";
        if (apiKeyEncrypted != null && !apiKeyEncrypted.isBlank()) {
            try { apiKey = cryptoUtils.decrypt(apiKeyEncrypted); } catch (Exception ignored) {}
        }

        String url = baseUrl.endsWith("/") ? baseUrl + "v1/models" : baseUrl + "/v1/models";

        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10)).build();
        var reqBuilder = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .header("Accept", "application/json")
                .GET()
                .timeout(java.time.Duration.ofSeconds(15));
        if (!apiKey.isBlank()) {
            reqBuilder.header("Authorization", "Bearer " + apiKey);
        }
        java.net.http.HttpResponse<String> response = client.send(reqBuilder.build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new BusinessException(502, "获取模型列表失败: HTTP " + response.statusCode());
        }

        var json = objectMapper.readTree(response.body());
        List<Map<String, Object>> models = new java.util.ArrayList<>();
        var dataNode = json.has("data") ? json.get("data") : json;
        if (dataNode.isArray()) {
            for (var node : dataNode) {
                Map<String, Object> model = new LinkedHashMap<>();
                model.put("id", node.path("id").asText());
                model.put("name", node.path("id").asText());
                models.add(model);
            }
        }
        return models;
    }

    private List<Map<String, Object>> fetchAnthropicModels() {
        // Anthropic doesn't have a public model list API, return known models
        return List.of(
            Map.of("id", "claude-sonnet-4-20250514", "name", "Claude Sonnet 4"),
            Map.of("id", "claude-opus-4-20250514", "name", "Claude Opus 4"),
            Map.of("id", "claude-3-5-haiku-20241022", "name", "Claude 3.5 Haiku")
        );
    }

    @GetMapping("/quota")
    public R<Map<String, Object>> getQuotaUsage(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        Map<String, Object> quota = new LinkedHashMap<>();
        quota.put("used", quotaService.getUsage(userId));
        quota.put("limit", quotaService.getDailyLimit());
        return R.ok(quota);
    }

    private AiConfigVO.ProviderConfigVO buildProviderConfig(String provider, boolean hasApiKey) {
        String prefix = "ai." + provider;
        return AiConfigVO.ProviderConfigVO.builder()
                .provider(provider)
                .apiKey(hasApiKey ? maskApiKey(getConfigValue(prefix + ".api-key")) : "")
                .baseUrl(getConfigValue(prefix + ".base-url"))
                .model(getConfigValue(prefix + ".model"))
                .temperature(getDoubleConfigValue(prefix + ".temperature", null))
                .topP(getDoubleConfigValue(prefix + ".top-p", null))
                .maxTokens(getIntConfigValueOrNull(prefix + ".max-tokens"))
                .build();
    }

    private AiConfigVO.EmbeddingConfigVO buildEmbeddingConfig() {
        String provider = getConfigValue("ai.memory.embedding-provider");
        return AiConfigVO.EmbeddingConfigVO.builder()
                .provider(provider != null ? provider : "openai")
                .model(getConfigValue("ai.memory.embedding-model"))
                .apiKey(maskApiKey(getConfigValue("ai.memory.embedding-api-key")))
                .baseUrl(getConfigValue("ai.memory.embedding-base-url"))
                .build();
    }

    private AiConfigVO.OrchestratorConfigVO buildOrchestratorConfig() {
        return AiConfigVO.OrchestratorConfigVO.builder()
                .maxIterations(getIntConfigValue("ai.orchestrator.max-iterations", 10))
                .maxToolCalls(getIntConfigValue("ai.orchestrator.max-tool-calls", 15))
                .maxConsecutiveErrors(getIntConfigValue("ai.orchestrator.max-consecutive-errors", 3))
                .sopEnabled(getBooleanConfigValue("ai.memory.sop-enabled", true))
                .memoryEnabled(getBooleanConfigValue("ai.memory.enabled", true))
                .systemPromptOverride(getConfigValue("ai.orchestrator.system-prompt-override"))
                .build();
    }

    private ChatModel createTestModel(AiTestRequest request) {
        String apiKey = resolveTestApiKey(request);

        return switch (request.getProvider()) {
            case "openai" -> {
                String baseUrl = request.getBaseUrl() != null ? request.getBaseUrl() : "https://api.openai.com";
                String model = request.getModel() != null ? request.getModel() : "gpt-4o";
                var api = OpenAiApi.builder().apiKey(apiKey).baseUrl(baseUrl).build();
                yield OpenAiChatModel.builder()
                        .openAiApi(api)
                        .defaultOptions(OpenAiChatOptions.builder().model(model).build())
                        .build();
            }
            case "anthropic" -> {
                String model = request.getModel() != null ? request.getModel() : "claude-sonnet-4-20250514";
                var api = AnthropicApi.builder().apiKey(apiKey).build();
                yield AnthropicChatModel.builder()
                        .anthropicApi(api)
                        .defaultOptions(AnthropicChatOptions.builder().model(model).build())
                        .build();
            }
            case "ollama" -> {
                String baseUrl = request.getBaseUrl() != null ? request.getBaseUrl() : "http://localhost:11434";
                String model = request.getModel() != null ? request.getModel() : "llama3";
                var api = OllamaApi.builder().baseUrl(baseUrl).build();
                yield OllamaChatModel.builder()
                        .ollamaApi(api)
                        .defaultOptions(OllamaChatOptions.builder().model(model).build())
                        .build();
            }
            case "gemini" -> {
                String baseUrl = request.getBaseUrl() != null ? request.getBaseUrl()
                        : "https://generativelanguage.googleapis.com/v1beta/openai/";
                String model = request.getModel() != null ? request.getModel() : "gemini-2.0-flash";
                var api = OpenAiApi.builder().apiKey(apiKey).baseUrl(baseUrl).build();
                yield OpenAiChatModel.builder()
                        .openAiApi(api)
                        .defaultOptions(OpenAiChatOptions.builder().model(model).build())
                        .build();
            }
            case "github-copilot" -> {
                String bearerToken = copilotAuthService.getCopilotBearerToken();
                String baseUrl = request.getBaseUrl() != null ? request.getBaseUrl()
                        : "https://api.githubcopilot.com";
                String model = request.getModel() != null ? request.getModel() : "gpt-4o";
                var copilotHeaders = new LinkedMultiValueMap<String, String>();
                copilotHeaders.add("Editor-Version", "vscode/1.80.1");
                copilotHeaders.add("Editor-Plugin-Version", "copilot.vim/1.16.0");
                copilotHeaders.add("Copilot-Integration-Id", "vscode-chat");
                copilotHeaders.add("User-Agent", "GithubCopilot/1.155.0");
                var api = OpenAiApi.builder().apiKey(bearerToken).baseUrl(baseUrl).completionsPath("/chat/completions").headers(copilotHeaders).build();
                yield OpenAiChatModel.builder()
                        .openAiApi(api)
                        .defaultOptions(OpenAiChatOptions.builder().model(model).build())
                        .build();
            }
            default -> throw new IllegalArgumentException("不支持的 Provider: " + request.getProvider());
        };
    }

    private String resolveTestApiKey(AiTestRequest request) {
        if (request.getApiKey() != null && !request.getApiKey().isBlank()
                && !request.getApiKey().contains(MASKED_KEY_PATTERN)) {
            return request.getApiKey();
        }
        String prefix = "ai." + request.getProvider();
        String encrypted = getConfigValue(prefix + ".api-key");
        if (encrypted == null || encrypted.isBlank()) {
            return "";
        }
        try {
            return cryptoUtils.decrypt(encrypted);
        } catch (Exception e) {
            return "";
        }
    }

    private Map<String, AiConfigVO.ChannelConfigVO> buildChannelConfigs() {
        Map<String, AiConfigVO.ChannelConfigVO> channels = new LinkedHashMap<>();
        CHANNEL_SETTINGS_KEYS.forEach((channel, keys) -> {
            String prefix = "ai.channel." + channel;
            boolean channelEnabled = "true".equals(getConfigValue(prefix + ".enabled"));
            Map<String, String> settings = new LinkedHashMap<>();
            for (String key : keys) {
                String value = getConfigValue(prefix + "." + key);
                if (CHANNEL_SENSITIVE_KEYS.contains(key) && value != null && !value.isBlank()) {
                    settings.put(key, maskApiKey(value));
                } else {
                    settings.put(key, value != null ? value : "");
                }
            }
            channels.put(channel, AiConfigVO.ChannelConfigVO.builder()
                    .channel(channel)
                    .enabled(channelEnabled)
                    .settings(settings)
                    .build());
        });
        return channels;
    }

    private AiConfigVO.ChannelContextVO buildChannelContextConfig() {
        return AiConfigVO.ChannelContextVO.builder()
                .contextMode(getConfigValue("ai.channel.context-mode") != null ? getConfigValue("ai.channel.context-mode") : "persistent")
                .sessionTimeout(getIntConfigValue("ai.channel.session-timeout", 30))
                .defaultProvider(getConfigValue("ai.channel.default-provider") != null ? getConfigValue("ai.channel.default-provider") : "")
                .defaultModel(getConfigValue("ai.channel.default-model") != null ? getConfigValue("ai.channel.default-model") : "")
                .build();
    }

    private String maskApiKey(String encryptedValue) {
        if (encryptedValue == null || encryptedValue.isBlank()) {
            return "";
        }
        try {
            String decrypted = cryptoUtils.decrypt(encryptedValue);
            if (decrypted.length() <= 7) {
                return "***";
            }
            return decrypted.substring(0, 4) + "***" + decrypted.substring(decrypted.length() - 3);
        } catch (Exception e) {
            return "***";
        }
    }

    private String getConfigValue(String key) {
        return systemConfigRepository.findByConfigKey(key)
                .map(SystemConfig::getConfigValue)
                .orElse(null);
    }

    private int getIntConfigValue(String key, int defaultValue) {
        String value = getConfigValue(key);
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private Integer getIntConfigValueOrNull(String key) {
        String value = getConfigValue(key);
        if (value == null || value.isBlank()) return null;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double getDoubleConfigValue(String key, Double defaultValue) {
        String value = getConfigValue(key);
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private boolean getBooleanConfigValue(String key, boolean defaultValue) {
        String value = getConfigValue(key);
        if (value == null || value.isBlank()) return defaultValue;
        return "true".equalsIgnoreCase(value) || "1".equals(value);
    }

    private void saveConfigValue(String key, String value) {
        SystemConfig config = systemConfigRepository.findByConfigKey(key).orElse(null);
        if (config != null) {
            config.setConfigValue(value);
        } else {
            config = new SystemConfig();
            config.setConfigKey(key);
            config.setConfigValue(value);
            config.setConfigGroup("ai");
        }
        systemConfigRepository.save(config);
    }
}
