package com.easyshell.server.ai.adaptive;

import com.easyshell.server.ai.config.AgenticConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;

/**
 * Filters available tools by task type.
 * Reduces irrelevant tools to improve LLM decision accuracy and lower token consumption.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolSetSelector {

    private final AgenticConfigService configService;

    // Universal tools available to all task types (always included)
    private static final Set<String> UNIVERSAL_TOOLS = Set.of(
            "getCurrentTime", "parseTime", "timeDiff",
            "calculate", "convertStorageUnit",
            "extractByRegex", "diffText", "textStats",
            "convertFormat", "prettyPrintJson", "extractJsonPath",
            "base64", "urlEncode", "hash"
    );

    // Read-only tool whitelists by task type.
    // Names MUST match the actual @Tool annotated method names in tool classes.
    // EXECUTE, TROUBLESHOOT, DEPLOY → null → all tools allowed.
    private static final Map<TaskType, Set<String>> TOOL_WHITELIST = Map.ofEntries(
            Map.entry(TaskType.QUERY, Set.of(
                    "listHosts", "listHostsByStatus", "getHostTags",
                    "listRecentTasks", "getTaskDetail",
                    "listScripts", "getScriptDetail",
                    "listClusters", "getClusterDetail",
                    "getDashboardStats", "getHostMetrics",
                    "queryAuditLogs", "listScheduledTasks", "getInspectReports",
                    "fetchUrl", "webSearch", "searchKnowledge"
            )),
            Map.entry(TaskType.MONITOR, Set.of(
                    "listHosts", "listHostsByStatus",
                    "getDashboardStats", "getHostMetrics",
                    "queryAuditLogs", "listScheduledTasks", "getInspectReports",
                    "triggerScheduledTask"
            )),
            Map.entry(TaskType.GENERAL, Set.of(
                    "listHosts", "listHostsByStatus",
                    "listRecentTasks", "listScripts", "listClusters",
                    "getDashboardStats",
                    "fetchUrl", "webSearch", "searchKnowledge"
            ))
    );

    /**
     * Select tools appropriate for the given task type.
     * Returns filtered array for QUERY/MONITOR/GENERAL, full array for EXECUTE/TROUBLESHOOT/DEPLOY.
     * Safety: if filtering produces 0 tools, falls back to all tools to prevent LLM from having no capabilities.
     */
    public ToolCallback[] selectTools(TaskType taskType, ToolCallback[] allTools) {
        if (!configService.getBoolean("ai.adaptive.enabled", true)) {
            return allTools;
        }

        Set<String> whitelist = TOOL_WHITELIST.get(taskType);
        if (whitelist == null) {
            return allTools;  // EXECUTE, TROUBLESHOOT, DEPLOY → all tools
        }

        ToolCallback[] filtered = Arrays.stream(allTools)
                .filter(t -> {
                    String name = t.getToolDefinition().name();
                    return whitelist.contains(name) || UNIVERSAL_TOOLS.contains(name);
                })
                .toArray(ToolCallback[]::new);

        // Safety fallback: if filtering removes ALL tools, return full set instead of empty
        if (filtered.length == 0 && allTools.length > 0) {
            log.warn("Task type {}: whitelist filtered ALL {} tools to 0 — falling back to full tool set. " +
                    "Check TOOL_WHITELIST names match actual @Tool method names.", taskType, allTools.length);
            return allTools;
        }

        log.debug("Task type {}: filtered {} -> {} tools", taskType, allTools.length, filtered.length);
        return filtered;
    }
}
