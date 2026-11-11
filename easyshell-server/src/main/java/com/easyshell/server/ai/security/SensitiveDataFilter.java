package com.easyshell.server.ai.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 敏感数据过滤器 — 在文本发送给 AI 模型前，对密码、API Key、Token、私钥等进行脱敏处理。
 */
@Slf4j
@Component
public class SensitiveDataFilter {

    private static final String MASK = "***";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private static final Pattern SENSITIVE_FIELD = Pattern.compile(
            "(?i).*(password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key|private[_-]?key|credential).*" );

    /** Structured field names carry context that is lost when values are filtered alone. */
    public boolean isSensitiveField(String name) {
        return name != null && SENSITIVE_FIELD.matcher(name).matches();
    }

    public Object filterField(String name, Object value) {
        return isSensitiveField(name) && value != null ? MASK : value;
    }

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

        String trimmed = text.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                Object parsed = JSON.readerFor(Object.class)
                        .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .readValue(trimmed);
                return JSON.writeValueAsString(filterJson(parsed));
            } catch (Exception e) {
                // Never return malformed or ambiguous JSON containing unfiltered data.
                return MASK;
            }
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

    /** Filter structured data before serialization, including keys and sensitive fields. */
    public Object filterJson(Object value) {
        if (value instanceof String text) {
            return filter(text);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (var entry : map.entrySet()) {
                String name = String.valueOf(entry.getKey());
                String safeName = filter(name);
                if (result.containsKey(safeName)) {
                    throw new IllegalArgumentException("Filtered JSON keys conflict");
                }
                Object item = entry.getValue();
                result.put(safeName, isSensitiveField(name) && item != null ? MASK : filterJson(item));
            }
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            for (Object item : list) {
                result.add(filterJson(item));
            }
            return result;
        }
        return value;
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
