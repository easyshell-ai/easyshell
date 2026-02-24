package com.easyshell.server.ai.agent;

import com.easyshell.server.ai.orchestrator.OrchestratorEngine;
import com.easyshell.server.ai.service.ChatModelFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DelegateTaskTool {

    private final AgentDefinitionRepository agentDefinitionRepository;
    private final BackgroundTaskManager backgroundTaskManager;
    private final OrchestratorEngine orchestratorEngine;
    private final ChatModelFactory chatModelFactory;

    public DelegateTaskTool(
            AgentDefinitionRepository agentDefinitionRepository,
            BackgroundTaskManager backgroundTaskManager,
            @Lazy OrchestratorEngine orchestratorEngine,
            ChatModelFactory chatModelFactory) {
        this.agentDefinitionRepository = agentDefinitionRepository;
        this.backgroundTaskManager = backgroundTaskManager;
        this.orchestratorEngine = orchestratorEngine;
        this.chatModelFactory = chatModelFactory;
    }

    @Tool(description = "委派任务给子Agent执行。适用于需要并行处理或专门技能的复杂任务。简单查询不需要委派。调用前请先确认可用的agent类型。")
    public String delegateTask(
            @ToolParam(description = "子Agent类型，从可用列表中选择") String agentType,
            @ToolParam(description = "任务描述（简要说明）") String description,
            @ToolParam(description = "详细任务提示词，提供足够的上下文和具体要求") String prompt,
            @ToolParam(description = "是否异步执行: true=后台执行并立即返回taskId, false=同步等待结果返回") boolean async) {

        log.info("DelegateTask invoked: agent={}, async={}, desc={}", agentType, async, description);

        var availableAgents = agentDefinitionRepository.findByEnabledTrue().stream()
                .filter(a -> !"primary".equals(a.getMode()))
                .map(a -> a.getName() + "(" + (a.getDescription() != null ? a.getDescription() : a.getDisplayName()) + ")")
                .toList();

        AgentDefinition agentDef = agentDefinitionRepository.findByNameAndEnabledTrue(agentType)
                .orElse(null);
        if (agentDef == null) {
            return "错误: 未找到可用的Agent类型 '" + agentType + "'。当前可用类型: " + String.join(", ", availableAgents);
        }

        if ("primary".equals(agentDef.getMode())) {
            return "错误: 不能委派给主Agent";
        }

        try {
            if (async) {
                String taskId = backgroundTaskManager.submit(agentDef, prompt, orchestratorEngine, chatModelFactory);
                log.info("Async task submitted: taskId={}, agent={}", taskId, agentType);
                return "异步任务已提交。任务ID: " + taskId + "，可使用 get_task_result 工具查询结果。";
            } else {
                String result = orchestratorEngine.executeAsSubAgent(agentDef, prompt, chatModelFactory);
                log.info("Sync delegation completed: agent={}, resultLength={}", agentType, result != null ? result.length() : 0);
                return "<task_result agent=\"" + agentType + "\">\n" + (result != null ? result : "(无结果)") + "\n</task_result>";
            }
        } catch (Exception e) {
            log.error("DelegateTask failed: agent={}", agentType, e);
            return "委派任务失败: " + e.getMessage();
        }
    }
}
