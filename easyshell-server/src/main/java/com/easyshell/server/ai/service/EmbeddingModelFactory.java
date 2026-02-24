package com.easyshell.server.ai.service;

import com.easyshell.server.ai.config.AgenticConfigService;
import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.entity.SystemConfig;
import com.easyshell.server.repository.SystemConfigRepository;
import com.easyshell.server.util.CryptoUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factory for creating EmbeddingModel instances based on configuration.
 * Similar to ChatModelFactory but for embedding models.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingModelFactory {

    private final SystemConfigRepository systemConfigRepository;
    private final CryptoUtils cryptoUtils;
    private final AgenticConfigService agenticConfigService;

    private final Map<String, EmbeddingModel> modelCache = new ConcurrentHashMap<>();

    /**
     * Get the configured embedding model for memory/SOP features.
     * Reads from ai.memory.embedding-provider and ai.memory.embedding-model configs.
     */
    public EmbeddingModel getEmbeddingModel() {
        String provider = agenticConfigService.get("ai.memory.embedding-provider", "openai");
        String model = agenticConfigService.get("ai.memory.embedding-model", "text-embedding-3-small");
        return getEmbeddingModel(provider, model);
    }

    public EmbeddingModel getEmbeddingModel(@Nullable String provider, @Nullable String modelOverride) {
        if (provider == null || provider.isBlank() || "default".equals(provider)) {
            provider = agenticConfigService.get("ai.memory.embedding-provider", "openai");
        }
        if (modelOverride == null || modelOverride.isBlank()) {
            modelOverride = agenticConfigService.get("ai.memory.embedding-model", "text-embedding-3-small");
        }

        String cacheKey = provider + ":" + modelOverride;
        final String resolvedProvider = provider;
        final String resolvedModel = modelOverride;
        return modelCache.computeIfAbsent(cacheKey, k -> createEmbeddingModel(resolvedProvider, resolvedModel));
    }

    public void invalidateCache() {
        modelCache.clear();
        log.info("Embedding model cache invalidated");
    }

    private EmbeddingModel createEmbeddingModel(String provider, String model) {
        log.info("Creating embedding model: provider={}, model={}", provider, model);
        return switch (provider) {
            case "openai" -> createOpenAiEmbeddingModel(model);
            case "dashscope", "aliyun", "qwen" -> createDashScopeEmbeddingModel(model);
            case "ollama" -> createOllamaEmbeddingModel(model);
            case "gemini" -> createGeminiEmbeddingModel(model);
            default -> throw new BusinessException(400, "不支持的 Embedding Provider: " + provider + 
                    "。支持的 provider: openai, dashscope, ollama, gemini");
        };
    }

    private OpenAiEmbeddingModel createOpenAiEmbeddingModel(String model) {
        String apiKey = decryptConfig("ai.openai.api-key");
        String baseUrl = getConfigValue("ai.openai.base-url", "https://api.openai.com");

        var api = OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();

        return new OpenAiEmbeddingModel(
                api,
                MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder()
                        .model(model)
                        .build(),
                RetryUtils.DEFAULT_RETRY_TEMPLATE);
    }

    /**
     * DashScope (Aliyun) embedding using OpenAI-compatible API.
     * Supported models: text-embedding-v3, text-embedding-v2, text-embedding-v1
     */
    private OpenAiEmbeddingModel createDashScopeEmbeddingModel(String model) {
        String apiKey = decryptConfig("ai.openai.api-key");
        String baseUrl = getConfigValue("ai.openai.base-url", "https://dashscope.aliyuncs.com/compatible-mode/");

        var api = OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();

        return new OpenAiEmbeddingModel(
                api,
                MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder()
                        .model(model)
                        .build(),
                RetryUtils.DEFAULT_RETRY_TEMPLATE);
    }

    private OllamaEmbeddingModel createOllamaEmbeddingModel(String model) {
        String baseUrl = getConfigValue("ai.ollama.base-url", "http://localhost:11434");

        var api = OllamaApi.builder()
                .baseUrl(baseUrl)
                .build();

        return OllamaEmbeddingModel.builder()
                .ollamaApi(api)
                .defaultOptions(OllamaEmbeddingOptions.builder()
                        .model(model)
                        .build())
                .build();
    }

    /**
     * Gemini embedding uses OpenAI-compatible API.
     */
    private OpenAiEmbeddingModel createGeminiEmbeddingModel(String model) {
        String apiKey = decryptConfig("ai.gemini.api-key");
        String baseUrl = getConfigValue("ai.gemini.base-url",
                "https://generativelanguage.googleapis.com/v1beta/openai/");

        var api = OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();

        return new OpenAiEmbeddingModel(
                api,
                MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder()
                        .model(model)
                        .build(),
                RetryUtils.DEFAULT_RETRY_TEMPLATE);
    }

    private String getConfigValue(String key, String defaultValue) {
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
}
