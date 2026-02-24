package com.easyshell.server.ai.adaptive;

import com.easyshell.server.ai.chat.SystemPrompts;
import com.easyshell.server.ai.config.AgenticConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Dynamically assembles system prompt based on task type.
 * Structure: Base + TaskSpecific + Memory + SOP.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdaptivePromptBuilder {

    private final AgenticConfigService configService;

    /**
     * Build adaptive system prompt from task type, memory context, and SOP suggestion.
     */
    public String buildPrompt(TaskType taskType, String memoryContext, String sopSuggestion) {
        if (!configService.getBoolean("ai.adaptive.enabled", true)) {
            return buildFallbackPrompt(memoryContext, sopSuggestion);
        }

        StringBuilder sb = new StringBuilder();

        // 1. Base prompt
        sb.append(getBasePrompt());

        // 2. Task-specific section
        String taskPrompt = getTaskSpecificPrompt(taskType);
        if (taskPrompt != null) {
            sb.append("\n\n").append(taskPrompt);
        }

        // 3. Memory context
        if (memoryContext != null && !memoryContext.isEmpty()) {
            sb.append("\n\n## Relevant Historical Memory\n").append(memoryContext);
        }

        // 4. SOP suggestion
        if (sopSuggestion != null && !sopSuggestion.isEmpty()) {
            sb.append("\n\n## Recommended SOP\n").append(sopSuggestion);
        }

        return sb.toString();
    }

    private String getBasePrompt() {
        String custom = configService.get("ai.prompt.base", null);
        return custom != null ? custom : SystemPrompts.OPS_ASSISTANT;
    }

    private String getTaskSpecificPrompt(TaskType taskType) {
        String configKey = "ai.prompt.task." + taskType.name().toLowerCase();
        String custom = configService.get(configKey, null);
        if (custom != null) return custom;

        // Built-in defaults
        return switch (taskType) {
            case QUERY -> """
                    ## Query Mode
                    You are helping the user retrieve system information. Follow these rules:
                    1. Use read-only tools to collect information
                    2. Present results in a clear, structured format
                    3. Do NOT execute any modification operations
                    """;
            case EXECUTE -> """
                    ## Execution Mode
                    You are helping the user execute operations. Follow these rules:
                    1. Confirm the operation target and scope
                    2. Assess operation risk level
                    3. High-risk operations require user confirmation
                    4. Verify results after execution
                    """;
            case TROUBLESHOOT -> """
                    ## Troubleshooting Mode
                    You are helping the user diagnose issues. Follow the diagnostic process:
                    1. Gather symptoms: identify the problem, start time, impact scope
                    2. Collect information: check service status, logs, resources, network
                    3. Hypothesis testing: form hypotheses and verify one by one
                    4. Root cause analysis: find the root cause, not surface symptoms
                    5. Resolution: provide clear fix steps and prevention measures
                    Do not rush to fix — complete diagnosis before proposing solutions.
                    """;
            case DEPLOY -> """
                    ## Deploy / Configuration Mode
                    You are helping the user with deployment or configuration changes. Follow these rules:
                    1. Backup: confirm backup strategy before changes
                    2. Pre-check: verify target environment prerequisites
                    3. Execute: perform changes step by step per plan
                    4. Verify: validate results after each step
                    5. Rollback: ensure each step has a rollback plan
                    """;
            case MONITOR -> """
                    ## Monitoring & Analysis Mode
                    You are helping the user analyze monitoring data. Follow these rules:
                    1. Collect relevant metric data
                    2. Identify anomalous trends and threshold breaches
                    3. Correlate multi-dimensional metrics
                    4. Provide clear conclusions and recommendations
                    """;
            case GENERAL -> null;
        };
    }

    private String buildFallbackPrompt(String memoryContext, String sopSuggestion) {
        StringBuilder sb = new StringBuilder(SystemPrompts.OPS_ASSISTANT);
        if (memoryContext != null && !memoryContext.isEmpty()) {
            sb.append("\n\n## Relevant Historical Memory\n").append(memoryContext);
        }
        if (sopSuggestion != null && !sopSuggestion.isEmpty()) {
            sb.append("\n\n## Recommended SOP\n").append(sopSuggestion);
        }
        return sb.toString();
    }
}
