package com.easyshell.server.ai.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 敏感数据过滤器 — 在文本发送给 AI 模型前，对密码、API Key、Token、私钥等进行脱敏处理。
 */
@Slf4j
@Component
public class SensitiveDataFilter {

    private static final String MASK = "***";

    private static final List<Pattern> PATTERNS = List.of(
            // password=xxx, passwd:xxx, pwd=xxx (key-value pairs, various delimiters)
            Pattern.compile(
                    "(?i)(password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key|auth[_-]?token)\\s*[=:]\\s*['\"]?([^\\s'\"&;,}{\\]]+)",
                    Pattern.MULTILINE),

            // Bearer tokens
            Pattern.compile("(?i)(Bearer)\\s+([A-Za-z0-9\\-._~+/]+=*)", Pattern.MULTILINE),

            // OpenAI API keys: sk-...
            Pattern.compile("(sk-[A-Za-z0-9]{20,})", Pattern.MULTILINE),

            // AWS access keys: AKIA...
            Pattern.compile("(AKIA[A-Z0-9]{16})", Pattern.MULTILINE),

            // Private keys (PEM blocks)
            Pattern.compile("-----BEGIN\\s+(RSA\\s+)?PRIVATE\\s+KEY-----[\\s\\S]*?-----END\\s+(RSA\\s+)?PRIVATE\\s+KEY-----",
                    Pattern.MULTILINE),

            // MySQL/PostgreSQL connection strings with password
            Pattern.compile(
                    "(?i)(mysql|postgresql|postgres|jdbc)://[^:]+:([^@\\s]+)@",
                    Pattern.MULTILINE),

            // Generic hex/base64 tokens that look like secrets (32+ chars hex, or long base64)
            Pattern.compile("(?i)(api[_-]?key|secret[_-]?key|private[_-]?key)\\s*[=:]\\s*['\"]?([A-Za-z0-9+/=]{32,})",
                    Pattern.MULTILINE),

            // Environment variable exports with sensitive names
            Pattern.compile(
                    "(?i)export\\s+(\\w*(PASSWORD|SECRET|TOKEN|KEY|CREDENTIAL)\\w*)\\s*=\\s*['\"]?([^\\s'\"]+)",
                    Pattern.MULTILINE)
    );

    /**
     * 对文本中的敏感信息进行脱敏。
     *
     * @param text 原始文本
     * @return 脱敏后的文本
     */
    public String filter(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        String filtered = text;
        int replacements = 0;

        for (Pattern pattern : PATTERNS) {
            var matcher = pattern.matcher(filtered);
            if (matcher.find()) {
                String before = filtered;
                filtered = applyPattern(filtered, pattern);
                if (!filtered.equals(before)) {
                    replacements++;
                }
            }
        }

        if (replacements > 0) {
            log.debug("Filtered sensitive data: {} pattern(s) matched", replacements);
        }

        return filtered;
    }

    private String applyPattern(String text, Pattern pattern) {
        var matcher = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();

        while (matcher.find()) {
            String replacement;

            if (pattern.pattern().contains("BEGIN") && pattern.pattern().contains("PRIVATE")) {
                // Private key block → replace entire match
                replacement = "-----BEGIN PRIVATE KEY-----\n" + MASK + "\n-----END PRIVATE KEY-----";
            } else if (pattern.pattern().contains("Bearer")) {
                // Bearer token → keep prefix
                replacement = matcher.group(1) + " " + MASK;
            } else if (pattern.pattern().contains("mysql|postgresql") || pattern.pattern().contains("jdbc")) {
                // Connection string → mask password part
                String full = matcher.group(0);
                int colonIdx = full.indexOf("://");
                if (colonIdx >= 0) {
                    String prefix = full.substring(0, colonIdx + 3);
                    String rest = full.substring(colonIdx + 3);
                    int userColonIdx = rest.indexOf(':');
                    int atIdx = rest.indexOf('@');
                    if (userColonIdx >= 0 && atIdx > userColonIdx) {
                        replacement = prefix + rest.substring(0, userColonIdx + 1) + MASK + "@";
                    } else {
                        replacement = full;
                    }
                } else {
                    replacement = full;
                }
            } else if (pattern.pattern().contains("sk-")) {
                // OpenAI key → mask
                replacement = "sk-" + MASK;
            } else if (pattern.pattern().contains("AKIA")) {
                // AWS key → mask
                replacement = "AKIA" + MASK;
            } else if (pattern.pattern().contains("export")) {
                // export VAR=value → keep var name, mask value
                replacement = "export " + matcher.group(1) + "=" + MASK;
            } else if (matcher.groupCount() >= 2) {
                // Key-value pairs → keep key, mask value
                replacement = matcher.group(1) + "=" + MASK;
            } else {
                replacement = MASK;
            }

            matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
