package com.easyshell.server.ai.service;

import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.entity.SystemConfig;
import com.easyshell.server.repository.SystemConfigRepository;
import com.easyshell.server.util.CryptoUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GitHub Copilot OAuth Device Flow authentication service.
 *
 * Flow:
 * 1. Request device code from GitHub
 * 2. User visits verification URL and enters the user code
 * 3. Poll GitHub for access token
 * 4. Exchange GitHub OAuth token for Copilot API token
 * 5. Cache and auto-refresh Copilot token
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CopilotAuthService {

    private final SystemConfigRepository systemConfigRepository;
    private final CryptoUtils cryptoUtils;
    private final ObjectMapper objectMapper;

    private static final String CLIENT_ID = "Iv1.b507a08c87ecfe98";
    private static final String SCOPE = "read:user";
    private static final String DEVICE_CODE_URL = "https://github.com/login/device/code";
    private static final String ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token";
    private static final String COPILOT_TOKEN_URL = "https://api.github.com/copilot_internal/v2/token";

    private static final String CONFIG_KEY_OAUTH_TOKEN = "ai.github-copilot.oauth-token";

    // In-memory cache for the short-lived Copilot bearer token
    private volatile String cachedCopilotToken;
    private volatile Instant cachedTokenExpiry = Instant.EPOCH;

    // Store active device flow sessions (device_code -> expiry)
    private final Map<String, DeviceFlowSession> activeDeviceFlows = new ConcurrentHashMap<>();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public record DeviceCodeResponse(
            String deviceCode,
            String userCode,
            String verificationUri,
            int expiresIn,
            int interval
    ) {}

    private record DeviceFlowSession(String deviceCode, Instant expiry, int interval) {}

    /**
     * Step 1: Request a device code from GitHub.
     */
    public DeviceCodeResponse requestDeviceCode() {
        try {
            String body = "client_id=" + URLEncoder.encode(CLIENT_ID, StandardCharsets.UTF_8)
                    + "&scope=" + URLEncoder.encode(SCOPE, StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(DEVICE_CODE_URL))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("GitHub device code request failed: {} {}", response.statusCode(), response.body());
                throw new BusinessException(502, "GitHub 设备码请求失败: HTTP " + response.statusCode());
            }

            JsonNode json = objectMapper.readTree(response.body());

            if (json.has("error")) {
                throw new BusinessException(502, "GitHub 设备码请求失败: " + json.get("error_description").asText());
            }

            String deviceCode = json.get("device_code").asText();
            String userCode = json.get("user_code").asText();
            String verificationUri = json.get("verification_uri").asText();
            int expiresIn = json.get("expires_in").asInt(900);
            int interval = json.get("interval").asInt(5);

            // Store the session for later polling
            activeDeviceFlows.put(deviceCode, new DeviceFlowSession(
                    deviceCode,
                    Instant.now().plusSeconds(expiresIn),
                    interval
            ));

            log.info("GitHub device code requested successfully. User code: {}", userCode);

            return new DeviceCodeResponse(deviceCode, userCode, verificationUri, expiresIn, interval);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to request GitHub device code", e);
            throw new BusinessException(502, "GitHub 设备码请求失败: " + e.getMessage());
        }
    }

    /**
     * Step 2: Poll GitHub for the access token.
     * Returns a map with "status" and optionally "access_token".
     * Status can be: "authorization_pending", "slow_down", "expired_token", "access_denied", "success"
     */
    public Map<String, String> pollForToken(String deviceCode) {
        DeviceFlowSession session = activeDeviceFlows.get(deviceCode);
        if (session != null && Instant.now().isAfter(session.expiry())) {
            activeDeviceFlows.remove(deviceCode);
            return Map.of("status", "expired_token", "message", "设备码已过期，请重新发起授权");
        }

        try {
            String body = "client_id=" + URLEncoder.encode(CLIENT_ID, StandardCharsets.UTF_8)
                    + "&device_code=" + URLEncoder.encode(deviceCode, StandardCharsets.UTF_8)
                    + "&grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:device_code", StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ACCESS_TOKEN_URL))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode json = objectMapper.readTree(response.body());

            if (json.has("error")) {
                String error = json.get("error").asText();
                return switch (error) {
                    case "authorization_pending" -> Map.of("status", "authorization_pending", "message", "等待用户授权...");
                    case "slow_down" -> Map.of("status", "slow_down", "message", "请降低轮询频率");
                    case "expired_token" -> {
                        activeDeviceFlows.remove(deviceCode);
                        yield Map.of("status", "expired_token", "message", "设备码已过期，请重新发起授权");
                    }
                    case "access_denied" -> {
                        activeDeviceFlows.remove(deviceCode);
                        yield Map.of("status", "access_denied", "message", "用户拒绝了授权");
                    }
                    default -> Map.of("status", "error", "message", json.path("error_description").asText(error));
                };
            }

            // Success! We got the access token
            String accessToken = json.get("access_token").asText();
            activeDeviceFlows.remove(deviceCode);

            // Persist the OAuth token (encrypted) to database
            saveOAuthToken(accessToken);

            // Immediately exchange for Copilot token to verify it works
            try {
                exchangeForCopilotToken(accessToken);
                log.info("GitHub Copilot OAuth authorization successful");
            } catch (Exception e) {
                log.warn("Got OAuth token but Copilot token exchange failed (user may not have Copilot subscription): {}", e.getMessage());
            }

            return Map.of("status", "success", "message", "GitHub Copilot 授权成功");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to poll GitHub for token", e);
            throw new BusinessException(502, "GitHub token 轮询失败: " + e.getMessage());
        }
    }

    /**
     * Get the current Copilot bearer token, auto-refreshing if needed.
     * This is the token used for actual API calls to api.githubcopilot.com.
     */
    public String getCopilotBearerToken() {
        // Check cache first
        if (cachedCopilotToken != null && Instant.now().isBefore(cachedTokenExpiry.minusSeconds(60))) {
            return cachedCopilotToken;
        }

        // Get stored OAuth token
        String oauthToken = getStoredOAuthToken();
        if (oauthToken == null || oauthToken.isBlank()) {
            throw new BusinessException(401, "GitHub Copilot 未授权，请先完成 OAuth 设备码授权流程");
        }

        return exchangeForCopilotToken(oauthToken);
    }

    /**
     * Check if we have a stored OAuth token (i.e., user has authorized).
     */
    public boolean isAuthenticated() {
        String token = getStoredOAuthToken();
        return token != null && !token.isBlank();
    }

    /**
     * Logout: remove the stored OAuth token and clear cache.
     */
    public void logout() {
        systemConfigRepository.findByConfigKey(CONFIG_KEY_OAUTH_TOKEN).ifPresent(config -> {
            config.setConfigValue("");
            systemConfigRepository.save(config);
        });
        cachedCopilotToken = null;
        cachedTokenExpiry = Instant.EPOCH;
        log.info("GitHub Copilot OAuth token cleared");
    }

    /**
     * Invalidate the cached copilot token (forces re-exchange on next call).
     */
    public void invalidateTokenCache() {
        cachedCopilotToken = null;
        cachedTokenExpiry = Instant.EPOCH;
    }

    /**
     * List available models from GitHub Copilot API.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listModels() {
        String bearerToken = getCopilotBearerToken();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.githubcopilot.com/models"))
                    .header("Authorization", "Bearer " + bearerToken)
                    .header("Accept", "application/json")
                    .header("Editor-Version", "vscode/1.80.1")
                    .header("Editor-Plugin-Version", "copilot.vim/1.16.0")
                    .header("Copilot-Integration-Id", "vscode-chat")
                    .header("User-Agent", "GithubCopilot/1.155.0")
                    .GET()
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Failed to list Copilot models: {} {}", response.statusCode(), response.body());
                throw new BusinessException(502, "获取 Copilot 模型列表失败: HTTP " + response.statusCode());
            }

            JsonNode json = objectMapper.readTree(response.body());
            List<Map<String, Object>> models = new ArrayList<>();
            JsonNode data = json.has("data") ? json.get("data") : json;
            if (data.isArray()) {
                for (JsonNode node : data) {
                    Map<String, Object> model = new LinkedHashMap<>();
                    model.put("id", node.path("id").asText());
                    model.put("name", node.path("name").asText(node.path("id").asText()));
                    model.put("version", node.path("version").asText(""));
                    models.add(model);
                }
            }
            log.info("Fetched {} models from Copilot API", models.size());
            return models;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to list Copilot models", e);
            throw new BusinessException(502, "获取 Copilot 模型列表失败: " + e.getMessage());
        }
    }

    /**
     * Exchange GitHub OAuth token for a short-lived Copilot API token.
     */
    private String exchangeForCopilotToken(String oauthToken) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(COPILOT_TOKEN_URL))
                    .header("Authorization", "token " + oauthToken)
                    .header("Accept", "application/json")
                    .header("Editor-Version", "EasyShell/1.0")
                    .header("Editor-Plugin-Version", "EasyShell/1.0")
                    .header("Copilot-Integration-Id", "vscode-chat")
                    .GET()
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 401) {
                // OAuth token is invalid/revoked
                log.warn("GitHub OAuth token is invalid or revoked (401)");
                logout();
                throw new BusinessException(401, "GitHub 授权已失效，请重新授权");
            }

            if (response.statusCode() != 200) {
                log.error("Copilot token exchange failed: {} {}", response.statusCode(), response.body());
                throw new BusinessException(502, "Copilot token 交换失败: HTTP " + response.statusCode());
            }

            JsonNode json = objectMapper.readTree(response.body());
            String token = json.get("token").asText();
            long expiresAt = json.get("expires_at").asLong();

            // Cache the token
            cachedCopilotToken = token;
            cachedTokenExpiry = Instant.ofEpochSecond(expiresAt);

            log.debug("Copilot token exchanged successfully, expires at {}", cachedTokenExpiry);
            return token;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to exchange Copilot token", e);
            throw new BusinessException(502, "Copilot token 交换失败: " + e.getMessage());
        }
    }

    private void saveOAuthToken(String token) {
        String encrypted = cryptoUtils.encrypt(token);
        SystemConfig config = systemConfigRepository.findByConfigKey(CONFIG_KEY_OAUTH_TOKEN).orElse(null);
        if (config != null) {
            config.setConfigValue(encrypted);
        } else {
            config = new SystemConfig();
            config.setConfigKey(CONFIG_KEY_OAUTH_TOKEN);
            config.setConfigValue(encrypted);
            config.setConfigGroup("ai");
            config.setDescription("GitHub Copilot OAuth Token（加密存储）");
        }
        systemConfigRepository.save(config);
        log.info("GitHub OAuth token saved to database (encrypted)");
    }

    private String getStoredOAuthToken() {
        return systemConfigRepository.findByConfigKey(CONFIG_KEY_OAUTH_TOKEN)
                .map(SystemConfig::getConfigValue)
                .filter(v -> !v.isBlank())
                .map(encrypted -> {
                    try {
                        return cryptoUtils.decrypt(encrypted);
                    } catch (Exception e) {
                        log.warn("Failed to decrypt stored OAuth token", e);
                        return null;
                    }
                })
                .orElse(null);
    }
}
