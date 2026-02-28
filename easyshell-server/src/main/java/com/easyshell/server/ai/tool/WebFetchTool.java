package com.easyshell.server.ai.tool;

import com.easyshell.server.ai.config.AgenticConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebFetchTool {

    private final AgenticConfigService configService;

    private static final List<Pattern> DEFAULT_BLOCKED_PATTERNS = List.of(
            Pattern.compile("^localhost$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^127\\..*"),
            Pattern.compile("^10\\..*"),
            Pattern.compile("^172\\.(1[6-9]|2\\d|3[01])\\..*"),
            Pattern.compile("^192\\.168\\..*"),
            Pattern.compile("^0\\..*"),
            Pattern.compile("^\\[::1\\]$"),
            Pattern.compile("^169\\.254\\..*")
    );

    @Tool(description = "获取指定 URL 的网页内容并提取纯文本。适用于用户提供了一个网址需要 AI 分析其内容的场景。支持 HTTP/HTTPS 网页。注意：返回的是提取后的纯文本，不包含 HTML 标签、脚本、样式等。")
    public String fetchUrl(
            @ToolParam(description = "要获取的完整 URL 地址，必须以 http:// 或 https:// 开头") String url) {
        try {
            // Check if web access is enabled
            if (!configService.getBoolean("ai.web.enabled", true)) {
                return "网络访问功能已关闭，请联系管理员启用 ai.web.enabled 配置";
            }

            if (url == null || url.isBlank()) {
                return "URL 不能为空";
            }

            url = url.trim();
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return "URL 格式无效，必须以 http:// 或 https:// 开头";
            }

            // SSRF protection: block internal network addresses
            URI uri = new URI(url);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return "URL 格式无效，无法解析主机名";
            }

            if (isBlockedHost(host)) {
                return "安全限制：禁止访问内网地址 " + host;
            }

            // Resolve DNS and check if IP is internal
            try {
                InetAddress addr = InetAddress.getByName(host);
                if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()
                        || addr.isSiteLocalAddress() || addr.isAnyLocalAddress()) {
                    return "安全限制：禁止访问内网地址（DNS 解析到内网 IP）";
                }
            } catch (Exception e) {
                return "无法解析域名：" + host;
            }

            // Fetch page
            int timeout = configService.getInt("ai.web.fetch-timeout-ms", 10000);
            int maxSize = configService.getInt("ai.web.max-body-size-bytes", 1048576);

            Document doc = Jsoup.connect(url)
                    .userAgent("EasyShell-AI/1.0")
                    .timeout(timeout)
                    .maxBodySize(maxSize)
                    .followRedirects(true)
                    .get();

            // Remove non-content elements
            doc.select("script, style, nav, footer, header, noscript, aside, iframe, form, svg").remove();

            // Extract title and body text
            String title = doc.title();
            String bodyText = doc.body() != null ? doc.body().text() : "";

            if (bodyText.isBlank()) {
                return String.format("【网页标题】%s\n【来源 URL】%s\n【正文内容】（页面无文本内容）", title, url);
            }

            // Truncate
            int maxChars = configService.getInt("ai.web.max-content-chars", 8000);
            if (bodyText.length() > maxChars) {
                bodyText = bodyText.substring(0, maxChars) + "\n... [内容已截断，共 " + bodyText.length() + " 字符]";
            }

            return String.format("【网页标题】%s\n【来源 URL】%s\n【正文内容】\n%s", title, url, bodyText);

        } catch (Exception e) {
            log.warn("Failed to fetch URL {}: {}", url, e.getMessage());
            return "获取网页内容失败：" + e.getMessage();
        }
    }

    private boolean isBlockedHost(String host) {
        for (Pattern pattern : DEFAULT_BLOCKED_PATTERNS) {
            if (pattern.matcher(host).matches()) {
                return true;
            }
        }
        return false;
    }
}
