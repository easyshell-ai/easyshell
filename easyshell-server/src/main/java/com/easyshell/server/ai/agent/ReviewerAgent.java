package com.easyshell.server.ai.agent;

import com.easyshell.server.ai.config.AgenticConfigService;
import com.easyshell.server.ai.orchestrator.ExecutionPlan;
import com.easyshell.server.ai.orchestrator.OrchestratorEngine;
import com.easyshell.server.ai.service.ChatModelFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ReviewerAgent {

    private final OrchestratorEngine orchestratorEngine;
    private final AgentDefinitionRepository agentDefinitionRepository;
    private final ChatModelFactory chatModelFactory;
    private final AgenticConfigService agenticConfigService;
    private final MessageSource messageSource;

    public ReviewerAgent(
            @Lazy OrchestratorEngine orchestratorEngine,
            AgentDefinitionRepository agentDefinitionRepository,
            ChatModelFactory chatModelFactory,
            AgenticConfigService agenticConfigService,
            MessageSource messageSource) {
        this.orchestratorEngine = orchestratorEngine;
        this.agentDefinitionRepository = agentDefinitionRepository;
        this.chatModelFactory = chatModelFactory;
        this.agenticConfigService = agenticConfigService;
        this.messageSource = messageSource;
    }

    public String review(ExecutionPlan plan, String userMsg) {
        boolean reviewEnabled = agenticConfigService.getBoolean("ai.review.enabled", true);
        if (!reviewEnabled) {
            log.debug("Review is disabled via config");
            return null;
        }

        Optional<AgentDefinition> reviewerOpt = agentDefinitionRepository.findByNameAndEnabledTrue("reviewer");
        if (reviewerOpt.isEmpty()) {
            log.debug("Reviewer agent not found or disabled, skipping review");
            return null;
        }

        AgentDefinition reviewer = reviewerOpt.get();

        String reviewPrompt = buildReviewPrompt(plan, userMsg);

        try {
            String result = orchestratorEngine.executeAsSubAgent(reviewer, reviewPrompt, chatModelFactory);
            log.info("Review completed for plan with {} steps", plan.getSteps().size());
            return result;
        } catch (Exception e) {
            log.error("Review failed: {}", e.getMessage(), e);
            return null;
        }
    }

    private String buildReviewPrompt(ExecutionPlan plan, String userMsg) {
        StringBuilder sb = new StringBuilder();
        sb.append("请验证以下执行计划的结果。\n\n");
        sb.append("## 用户原始请求\n");
        sb.append(userMsg != null ? userMsg : "(无)");
        sb.append("\n\n## 执行计划\n");
        sb.append("目标: ").append(plan.getSummary() != null ? plan.getSummary() : "(无)").append("\n");
        sb.append("风险等级: ").append(plan.getEstimatedRisk() != null ? plan.getEstimatedRisk() : "UNKNOWN").append("\n\n");
        sb.append("## 步骤执行结果\n\n");

        if (plan.getSteps() != null) {
            for (ExecutionPlan.PlanStep step : plan.getSteps()) {
                sb.append(String.format("### 步骤 %d: %s\n", step.getIndex(), step.getDescription()));
                sb.append("- Agent: ").append(step.getAgent() != null ? step.getAgent() : "execute").append("\n");
                sb.append("- 状态: ").append(step.getStatus() != null ? step.getStatus() : "unknown").append("\n");
                if (step.getResult() != null) {
                    String result = step.getResult().length() > 1000
                            ? step.getResult().substring(0, 1000) + "...(truncated)"
                            : step.getResult();
                    sb.append("- 结果: ").append(result).append("\n");
                }
                if (step.getError() != null) {
                    sb.append("- 错误: ").append(step.getError()).append("\n");
                }
                sb.append("\n");
            }
        }

        sb.append("## 验证要求\n");
        sb.append("1. 检查所有步骤是否成功完成\n");
        sb.append("2. 验证执行结果是否符合用户原始请求\n");
        sb.append("3. 检查是否有安全隐患或遗漏\n");
        sb.append("4. 给出 PASS / PARTIAL / FAIL 的评估\n");

        return sb.toString();
    }

    private String i18n(String key, Object... args) {
        try {
            return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
        } catch (Exception e) {
            return key;
        }
    }
}
