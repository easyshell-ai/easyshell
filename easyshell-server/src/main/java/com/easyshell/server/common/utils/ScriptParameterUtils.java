package com.easyshell.server.common.utils;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ScriptParameterUtils {

    private static final Pattern PARAM_PATTERN = Pattern.compile("\\{\\{\\s*(\\w+)\\s*}}");

    private ScriptParameterUtils() {}

    public static Set<String> extractParameters(String content) {
        if (content == null || content.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> params = new LinkedHashSet<>();
        Matcher matcher = PARAM_PATTERN.matcher(content);
        while (matcher.find()) {
            params.add(matcher.group(1));
        }
        return params;
    }

    public static String substituteParameters(String content, Map<String, String> parameters) {
        if (content == null || parameters == null || parameters.isEmpty()) {
            return content;
        }
        Matcher matcher = PARAM_PATTERN.matcher(content);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String paramName = matcher.group(1);
            String value = parameters.getOrDefault(paramName, matcher.group(0));
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    public static Set<String> findMissingParameters(String content, Map<String, String> parameters) {
        Set<String> required = extractParameters(content);
        if (parameters == null) {
            return required;
        }
        Set<String> missing = new LinkedHashSet<>();
        for (String param : required) {
            if (!parameters.containsKey(param) || parameters.get(param) == null) {
                missing.add(param);
            }
        }
        return missing;
    }
}
