package com.easyshell.server.ai.tool;

import com.easyshell.server.ai.config.AgenticConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebSearchTool {

    private final AgenticConfigService configService;
    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;

    @Tool(description = "使用搜索引擎搜索互联网信息。适用于需要获取实时信息、查找技术文档、搜索错误解决方案等场景。返回搜索结果的标题、URL 和内容摘要。")
    public String webSearch(
            @ToolParam(description = "搜索关键词") String query,
            @ToolParam(description = "返回结果数量，默认 5，最大 10") int maxResults) {
        try {
            if (!configService.getBoolean("ai.web.enabled", true)) {
                return "网络访问功能已关闭，请联系管理员启用 ai.web.enabled 配置";
            }

            if (query == null || query.isBlank()) {
                return "搜索关键词不能为空";
            }

            int limit = Math.max(1, Math.min(maxResults <= 0 ? 5 : maxResults, 10));
            String provider = configService.get("ai.web.search-provider", "tavily");

            return switch (provider) {
                case "tavily" -> searchViaTavily(query, limit);
                case "searxng" -> searchViaSearXNG(query, limit);
                default -> "未配置搜索引擎，请在系统设置中配置 ai.web.search-provider（支持：tavily、searxng）";
            };
        } catch (Exception e) {
            log.warn("Web search failed for query '{}': {}", query, e.getMessage());
            return "搜索失败：" + e.getMessage();
        }
    }

    private String searchViaTavily(String query, int maxResults) {
        String apiKey = configService.get("ai.web.tavily-api-key", "");
        if (apiKey == null || apiKey.isBlank()) {
            return "Tavily API Key 未配置，请在系统设置中配置 ai.web.tavily-api-key";
        }

        try {
            RestClient client = restClientBuilder.build();
            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "query", query,
                    "max_results", maxResults,
                    "search_depth", "basic"
            ));

            String responseBody = client.post()
                    .uri("https://api.tavily.com/search")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            return formatTavilyResults(responseBody, query);
        } catch (Exception e) {
            log.warn("Tavily search failed: {}", e.getMessage());
            return "Tavily 搜索失败：" + e.getMessage();
        }
    }

    private String formatTavilyResults(String responseBody, String query) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode results = root.get("results");

            if (results == null || !results.isArray() || results.isEmpty()) {
                return "搜索 \"" + query + "\" 未找到相关结果";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("搜索 \"").append(query).append("\" 的结果：\n\n");

            int index = 1;
            for (JsonNode result : results) {
                String title = result.has("title") ? result.get("title").asText() : "无标题";
                String url = result.has("url") ? result.get("url").asText() : "";
                String content = result.has("content") ? result.get("content").asText() : "";

                sb.append(index++).append(". **").append(title).append("**\n");
                if (!url.isEmpty()) {
                    sb.append("   链接：").append(url).append("\n");
                }
                if (!content.isEmpty()) {
                    // Truncate individual result content
                    if (content.length() > 500) {
                        content = content.substring(0, 500) + "...";
                    }
                    sb.append("   摘要：").append(content).append("\n");
                }
                sb.append("\n");
            }

            return sb.toString();
        } catch (Exception e) {
            return "解析搜索结果失败：" + e.getMessage();
        }
    }

    private String searchViaSearXNG(String query, int maxResults) {
        String baseUrl = configService.get("ai.web.searxng-url", "http://localhost:8888");

        try {
            RestClient client = restClientBuilder.build();
            String responseBody = client.get()
                    .uri(baseUrl + "/search?q={query}&format=json&engines=google,bing&pageno=1", query)
                    .retrieve()
                    .body(String.class);

            return formatSearXNGResults(responseBody, query, maxResults);
        } catch (Exception e) {
            log.warn("SearXNG search failed: {}", e.getMessage());
            return "SearXNG 搜索失败：" + e.getMessage() + "\n请确认 SearXNG 实例地址 (" + baseUrl + ") 是否正确且可访问";
        }
    }

    private String formatSearXNGResults(String responseBody, String query, int maxResults) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode results = root.get("results");

            if (results == null || !results.isArray() || results.isEmpty()) {
                return "搜索 \"" + query + "\" 未找到相关结果";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("搜索 \"").append(query).append("\" 的结果：\n\n");

            int index = 1;
            for (JsonNode result : results) {
                if (index > maxResults) break;

                String title = result.has("title") ? result.get("title").asText() : "无标题";
                String url = result.has("url") ? result.get("url").asText() : "";
                String content = result.has("content") ? result.get("content").asText() : "";

                sb.append(index++).append(". **").append(title).append("**\n");
                if (!url.isEmpty()) {
                    sb.append("   链接：").append(url).append("\n");
                }
                if (!content.isEmpty()) {
                    if (content.length() > 500) {
                        content = content.substring(0, 500) + "...";
                    }
                    sb.append("   摘要：").append(content).append("\n");
                }
                sb.append("\n");
            }

            return sb.toString();
        } catch (Exception e) {
            return "解析搜索结果失败：" + e.getMessage();
        }
    }
}
