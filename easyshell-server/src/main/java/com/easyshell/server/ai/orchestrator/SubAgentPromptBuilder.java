package com.easyshell.server.ai.orchestrator;

import java.util.List;

/**
 * Shared utility for building enriched sub-agent prompts.
 * Used by both PlanExecutor (simple mode) and DagExecutor (DAG mode)
 * to inject host context and tool hints into step descriptions.
 */
public final class SubAgentPromptBuilder {

    private SubAgentPromptBuilder() {}

    /**
     * Build an enriched prompt for the sub-agent that includes:
     * 1. The step description (original task)
     * 2. Host context — agentIds from the step's hosts or user's targetAgentIds
     * 3. Tool hints — which tools this step is expected to use
     */
    public static String buildSubAgentPrompt(ExecutionPlan.PlanStep step, OrchestratorRequest request) {
        StringBuilder prompt = new StringBuilder();
        prompt.append(step.getDescription());

        // Determine effective host list: user-selected targetAgentIds take priority (verified IDs from UI),
        // then fall back to step-level hosts (planner guess, may use hostname instead of agentId)
        List<String> effectiveHosts = null;
        if (request.getTargetAgentIds() != null && !request.getTargetAgentIds().isEmpty()) {
            effectiveHosts = request.getTargetAgentIds();
        } else if (step.getHosts() != null && !step.getHosts().isEmpty()) {
            effectiveHosts = step.getHosts();
        }

        if (effectiveHosts != null && !effectiveHosts.isEmpty()) {
            prompt.append("\n\n【目标主机】请在以下指定主机上执行操作（agentId列表）：");
            for (String hostId : effectiveHosts) {
                prompt.append("\n- agentId: ").append(hostId);
            }
            prompt.append("\n请务必使用上述agentId作为目标主机参数，不要调用listHosts重新查询。");
        }

        if (step.getTools() != null && !step.getTools().isEmpty()) {
            prompt.append("\n\n【可用工具提示】本步骤建议使用以下工具：");
            for (String tool : step.getTools()) {
                prompt.append("\n- ").append(tool);
            }
        }

        return prompt.toString();
    }
}
