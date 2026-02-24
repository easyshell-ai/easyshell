package com.easyshell.server.ai.adaptive;

import com.easyshell.server.ai.config.AgenticConfigService;
import com.easyshell.server.ai.service.ChatModelFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Classifies user messages into TaskType.
 * Phase 1: rule-based pattern matching (fast, zero-cost).
 * Phase 2 (optional): LLM classification fallback.
 * 
 * IMPORTANT: Pattern priority order matters! More specific action types (EXECUTE, DEPLOY, TROUBLESHOOT)
 * should be checked BEFORE generic query types (QUERY, MONITOR, GENERAL) to avoid misclassification.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskClassifier {

    private final AgenticConfigService configService;
    private final ChatModelFactory chatModelFactory;

    // Use LinkedHashMap to guarantee iteration order - priority matters!
    // Order: EXECUTE → DEPLOY → TROUBLESHOOT → MONITOR → QUERY → GENERAL (fallback)
    // This ensures action-oriented requests get classified correctly before read-only queries.
    private static final Map<TaskType, List<Pattern>> PATTERNS;
    
    static {
        PATTERNS = new LinkedHashMap<>();
        // 1. EXECUTE - highest priority for action commands
        PATTERNS.put(TaskType.EXECUTE, List.of(
                Pattern.compile("执行|运行|启动|停止|重启|安装|卸载|创建|删除|修改|清理|脚本"),
                Pattern.compile("(?i)execute|run|start|stop|restart|install|remove|create|delete|clean|script")
        ));
        // 2. DEPLOY - deployment actions
        PATTERNS.put(TaskType.DEPLOY, List.of(
                Pattern.compile("部署|配置|迁移|升级|发布|回滚|更新配置|上线"),
                Pattern.compile("(?i)deploy|configure|migrate|upgrade|release|rollback|publish")
        ));
        // 3. TROUBLESHOOT - diagnostic actions
        PATTERNS.put(TaskType.TROUBLESHOOT, List.of(
                Pattern.compile("排查|诊断|为什么|异常|错误|故障|挂了|宕机|不通|超时|慢|无法|失败"),
                Pattern.compile("(?i)troubleshoot|diagnose|why|error|fail|down|timeout|slow|crash|cannot|unable")
        ));
        // 4. MONITOR - monitoring queries (still needs some write tools for triggering)
        PATTERNS.put(TaskType.MONITOR, List.of(
                Pattern.compile("监控|告警|CPU|内存|磁盘|负载|趋势|指标|阈值|流量"),
                Pattern.compile("(?i)monitor|alert|cpu|memory|disk|load|metrics|threshold|traffic")
        ));
        // 5. QUERY - read-only queries (lowest priority, only matches if nothing else does)
        PATTERNS.put(TaskType.QUERY, List.of(
                Pattern.compile("查看|查询|列出|显示|多少|哪些|状态|版本|列表|看看"),
                Pattern.compile("(?i)show|list|get|status|how many|which|check|look")
        ));
    }

    /**
     * Classify user message into a TaskType.
     * Priority: config check → rule engine → optional LLM → GENERAL fallback.
     */
    public TaskType classify(String userMessage) {
        if (!configService.getBoolean("ai.adaptive.enabled", true)) {
            return TaskType.GENERAL;
        }
        if (userMessage == null || userMessage.isBlank()) {
            return TaskType.GENERAL;
        }

        String text = userMessage.toLowerCase();

        // Phase 1: Rule-based classification (fast, free)
        for (Map.Entry<TaskType, List<Pattern>> entry : PATTERNS.entrySet()) {
            for (Pattern pattern : entry.getValue()) {
                if (pattern.matcher(text).find()) {
                    log.debug("Task classified as {} by rule: {}", entry.getKey(), pattern.pattern());
                    return entry.getKey();
                }
            }
        }

        // Phase 2: Optional LLM classification
        if (configService.getBoolean("ai.adaptive.classifier-use-llm", false)) {
            return classifyWithLlm(userMessage);
        }

        return TaskType.GENERAL;
    }

    private TaskType classifyWithLlm(String userMessage) {
        try {
            ChatModel chatModel = chatModelFactory.getChatModel(null, null);
            String prompt = "Classify the following ops request into one of: QUERY, EXECUTE, TROUBLESHOOT, DEPLOY, MONITOR, GENERAL\n"
                    + "Output only the category name, nothing else.\n\n"
                    + "Request: " + userMessage;
            String result = chatModel.call(prompt).trim().toUpperCase();
            return TaskType.valueOf(result);
        } catch (Exception e) {
            log.warn("LLM classification failed, defaulting to GENERAL: {}", e.getMessage());
            return TaskType.GENERAL;
        }
    }
}
