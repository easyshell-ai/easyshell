package com.easyshell.server.ai.service;

import com.easyshell.server.ai.config.AgenticConfigService;
import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.entity.SystemConfig;
import com.easyshell.server.repository.SystemConfigRepository;
import com.easyshell.server.util.CryptoUtils;
import org.springframework.context.annotation.Lazy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.lang.Nullable;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
@Slf4j
@Service
public class ChatModelFactory {

    private final SystemConfigRepository systemConfigRepository;
    private final CryptoUtils cryptoUtils;
    private final AgenticConfigService agenticConfigService;
    private final CopilotAuthService copilotAuthService;

    public ChatModelFactory(SystemConfigRepository systemConfigRepository,
                            CryptoUtils cryptoUtils,
                            AgenticConfigService agenticConfigService,
                            @Lazy CopilotAuthService copilotAuthService) {
        this.systemConfigRepository = systemConfigRepository;
        this.cryptoUtils = cryptoUtils;
        this.agenticConfigService = agenticConfigService;
        this.copilotAuthService = copilotAuthService;
    }

    private final Map<String, ChatModel> modelCache = new ConcurrentHashMap<>();

    public int getContextWindowSize(String provider) {
        if (provider == null || provider.isBlank() || "default".equals(provider)) {
            provider = getConfigValue("ai.default.provider", "openai");
        }
        String configKey = "ai." + provider + ".context-window";
        int configured = agenticConfigService.getInt(configKey, -1);
        if (configured > 0) return configured;

        return switch (provider) {
            case "openai" -> 128_000;
            case "anthropic" -> 200_000;
            case "ollama" -> 8_000;
            case "gemini" -> 1_000_000;
            case "github-copilot" -> 128_000;
            default -> 8_000;
        };
    }

    public ChatModel getChatModel(@Nullable String provider) {
        return getChatModel(provider, null);
    }

    public ChatModel getChatModel(@Nullable String provider, @Nullable String modelOverride) {
        return getChatModel(provider, modelOverride, null);
    }

    /**
     * 解析模型，可选覆盖 temperature（工作流 llm 节点）。
     *
     * <p>temperature 为 null 时保持原有缓存语义；非 null 时按 provider/model/temperature
     * 组合缓存，避免不同节点互相污染默认参数。GitHub Copilot 的 reasoning/responses
     * 模型不接受 temperature，沿用其模型感知的默认选项。</p>
     */
    public ChatModel getChatModel(@Nullable String provider, @Nullable String modelOverride,
                                  @Nullable Double temperature) {
        if (provider == null || provider.isBlank() || "default".equals(provider)) {
            provider = getConfigValue("ai.default.provider", "openai");
        }
        // GitHub Copilot tokens are short-lived (~30min) — never cache the model
        if ("github-copilot".equals(provider)) {
            return createChatModel(provider, modelOverride, temperature);
        }
        if (temperature == null) {
            String cacheKey = modelOverride != null ? provider + ":" + modelOverride : provider;
            final String resolvedProvider = provider;
            return modelCache.computeIfAbsent(cacheKey, k -> createChatModel(resolvedProvider, modelOverride));
        }
        String cacheKey = provider + ":" + (modelOverride == null ? "" : modelOverride)
                + ":temperature=" + temperature;
        final String resolvedProvider = provider;
        return modelCache.computeIfAbsent(cacheKey,
                k -> createChatModel(resolvedProvider, modelOverride, temperature));
    }

    public void invalidateCache() {
        modelCache.clear();
        log.info("AI model cache invalidated");
    }

    private ChatModel createChatModel(String provider, @Nullable String modelOverride) {
        return createChatModel(provider, modelOverride, null);
    }

    private ChatModel createChatModel(String provider, @Nullable String modelOverride,
                                      @Nullable Double temperature) {
        return switch (provider) {
            case "openai" -> createOpenAiModel(modelOverride, temperature);
            case "anthropic" -> createAnthropicModel(modelOverride, temperature);
            case "ollama" -> createOllamaModel(modelOverride, temperature);
            case "gemini" -> createGeminiModel(modelOverride, temperature);
            case "github-copilot" -> createGithubCopilotModel(modelOverride, temperature);
            default -> throw new BusinessException(400, "不支持的 AI Provider: " + provider);
        };
    }

    /** temperature 缺省 0.7，与既有各 Provider 默认值一致 */
    private static double temperatureOr(Double temperature) {
        return temperature == null || !Double.isFinite(temperature) ? 0.7 : temperature;
    }

    private OpenAiChatModel createOpenAiModel(@Nullable String modelOverride, @Nullable Double temperature) {
        String apiKey = decryptConfig("ai.openai.api-key");
        String baseUrl = getConfigValue("ai.openai.base-url", "https://api.openai.com");
        String model = modelOverride != null ? modelOverride : getConfigValue("ai.openai.model", "gpt-4o");

        var api = OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .restClientBuilder(restClientWithTimeout())
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(model)
                        .temperature(temperatureOr(temperature))
                        .build())
                .build();
    }

    private AnthropicChatModel createAnthropicModel(@Nullable String modelOverride, @Nullable Double temperature) {
        String apiKey = decryptConfig("ai.anthropic.api-key");
        String model = modelOverride != null ? modelOverride : getConfigValue("ai.anthropic.model", "claude-sonnet-4-20250514");

        var api = AnthropicApi.builder()
                .apiKey(apiKey)
                .build();

        return AnthropicChatModel.builder()
                .anthropicApi(api)
                .defaultOptions(AnthropicChatOptions.builder()
                        .model(model)
                        .temperature(temperatureOr(temperature))
                        .build())
                .build();
    }

    private OllamaChatModel createOllamaModel(@Nullable String modelOverride, @Nullable Double temperature) {
        String baseUrl = getConfigValue("ai.ollama.base-url", "http://localhost:11434");
        String model = modelOverride != null ? modelOverride : getConfigValue("ai.ollama.model", "llama3");

        var api = OllamaApi.builder()
                .baseUrl(baseUrl)
                .build();

        return OllamaChatModel.builder()
                .ollamaApi(api)
                .defaultOptions(OllamaChatOptions.builder()
                        .model(model)
                        .temperature(temperatureOr(temperature))
                        .build())
                .build();
    }

    private OpenAiChatModel createGeminiModel(@Nullable String modelOverride, @Nullable Double temperature) {
        String apiKey = decryptConfig("ai.gemini.api-key");
        String baseUrl = getConfigValue("ai.gemini.base-url",
                "https://generativelanguage.googleapis.com/v1beta/openai/");
        String model = modelOverride != null ? modelOverride : getConfigValue("ai.gemini.model", "gemini-2.0-flash");

        var api = OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .restClientBuilder(restClientWithTimeout())
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(model)
                        .temperature(temperatureOr(temperature))
                        .build())
                .build();
    }

    private ChatModel createGithubCopilotModel(@Nullable String modelOverride,
                                               @Nullable Double temperature) {
        String bearerToken = copilotAuthService.getCopilotBearerToken();
        String baseUrl = copilotAuthService.getCopilotApiBaseUrl();
        String model = modelOverride != null ? modelOverride : getConfigValue("ai.github-copilot.model", "gpt-4o");

        if (requiresResponsesApi(model)) {
            log.info("Using Copilot Responses API (/responses) for model: {}", model);
            return new CopilotResponsesChatModel(baseUrl, bearerToken, model);
        }

        log.debug("Using Copilot Chat Completions API (/chat/completions) for model: {}", model);
        var api = OpenAiApi.builder()
                .apiKey(bearerToken)
                .baseUrl(baseUrl)
                .completionsPath("/chat/completions")
                .headers(copilotHeaders())
                .restClientBuilder(restClientWithTimeout())
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(buildCopilotChatOptions(model, temperature))
                .build();
    }

    public String getConfigValue(String key, String defaultValue) {
        return systemConfigRepository.findByConfigKey(key)
                .map(SystemConfig::getConfigValue)
                .filter(v -> !v.isBlank())
                .orElse(defaultValue);
    }

    private String decryptConfig(String key) {
        String encrypted = systemConfigRepository.findByConfigKey(key)
                .map(SystemConfig::getConfigValue)
                .filter(v -> !v.isBlank())
                .orElseThrow(() -> new BusinessException(400, "未配置 " + key + "，请先在 AI 配置中设置"));
        try {
            return cryptoUtils.decrypt(encrypted);
        } catch (Exception e) {
            throw new BusinessException(400, key + " 解密失败，请重新配置");
        }
    }

    private MultiValueMap<String, String> copilotHeaders() {
        var headers = new LinkedMultiValueMap<String, String>();
        headers.add("Editor-Version", "vscode/1.80.1");
        headers.add("Editor-Plugin-Version", "copilot.vim/1.16.0");
        headers.add("Copilot-Integration-Id", "vscode-chat");
        headers.add("User-Agent", "EasyShell/1.2.0");
        return headers;
    }

    /**
     * Build model-aware OpenAiChatOptions for GitHub Copilot.
     * Reasoning models (o1, o3-mini, o4-mini, etc.) reject temperature and require
     * maxCompletionTokens instead of maxTokens.
     */
    public static OpenAiChatOptions buildCopilotChatOptions(String model) {
        return buildCopilotChatOptions(model, null);
    }

    static OpenAiChatOptions buildCopilotChatOptions(String model, @Nullable Double temperature) {
        var builder = OpenAiChatOptions.builder().model(model);
        if (isCopilotReasoningModel(model)) {
            builder.maxCompletionTokens(16384);
        } else {
            builder.temperature(temperatureOr(temperature));
        }
        return builder.build();
    }

    private static final Set<String> COPILOT_RESPONSES_MODELS = Set.of(
            "gpt-5.4",
            "gpt-5.3-codex",
            "gpt-5.2", "gpt-5.2-codex",
            "gpt-5.1", "gpt-5.1-codex", "gpt-5.1-codex-max"
    );

    static boolean requiresResponsesApi(String model) {
        if (model == null) return false;
        return COPILOT_RESPONSES_MODELS.contains(model.toLowerCase());
    }

    // Reference: OpenCode's copilot.go CanReason model list
    private static final Set<String> COPILOT_REASONING_MODELS = Set.of(
            "o1", "o1-mini", "o1-preview",
            "o3-mini",
            "o4-mini"
    );

    static boolean isCopilotReasoningModel(String model) {
        if (model == null) return false;
        String lowerModel = model.toLowerCase();
        if (COPILOT_REASONING_MODELS.contains(lowerModel)) return true;
        return lowerModel.startsWith("o1-") || lowerModel.startsWith("o3-") || lowerModel.startsWith("o4-");
    }

    /**
     * Create a RestClient.Builder with configured connect and read timeouts.
     * LLM API calls can take 30-120s; default Reactor Netty timeout (~10s) is too short.
     */
    private RestClient.Builder restClientWithTimeout() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(30));
        factory.setReadTimeout(Duration.ofSeconds(180));
        return RestClient.builder().requestFactory(factory);
    }

    /**
     * Create a RetryTemplate with reduced retries, suitable for scheduled/background tasks.
     * Default Spring AI retry (10 attempts, exponential backoff) can block for 8+ minutes.
     */
    static RetryTemplate schedulerRetryTemplate() {
        return RetryTemplate.builder()
                .maxAttempts(3)
                .exponentialBackoff(Duration.ofSeconds(2), 2.0, Duration.ofSeconds(10))
                .build();
    }
}
