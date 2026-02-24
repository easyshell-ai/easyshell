package com.easyshell.server.ai.service;

import com.easyshell.server.ai.model.dto.AiAlertRequest;
import com.easyshell.server.ai.model.dto.AiExecutionRequest;
import com.easyshell.server.ai.model.vo.AiAlertAnalysis;
import com.easyshell.server.ai.model.vo.AiExecutionResult;
import com.easyshell.server.ai.risk.CommandRiskEngine;
import com.easyshell.server.ai.risk.RiskLevel;
import com.easyshell.server.ai.security.AiQuotaService;
import com.easyshell.server.ai.security.SensitiveDataFilter;
import com.easyshell.server.model.entity.Agent;
import com.easyshell.server.repository.AgentRepository;
import com.easyshell.server.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiAlertResponder {

    private final ChatModelFactory chatModelFactory;
    private final CommandRiskEngine riskEngine;
    private final AiExecutionService executionService;
    private final AgentRepository agentRepository;
    private final AuditLogService auditLogService;
    private final SensitiveDataFilter sensitiveDataFilter;
    private final AiQuotaService quotaService;

    private static final String ALERT_ANALYSIS_PROMPT = """
            你是一个专业的 Linux 运维 AI 助手。请分析以下告警信息，并给出：
            1. 告警分析：说明告警原因和可能影响
            2. 建议操作：给出具体的运维操作建议
            3. 风险等级：LOW / MEDIUM / HIGH
            4. 如果有安全可靠的自动修复脚本（仅限低风险只读命令），请提供
            
            请以 JSON 格式返回，字段为 analysis, suggestedAction, riskLevel, autoFixAvailable(boolean), autoFixScript(可选)
            
            告警信息：
            """;

    public AiAlertAnalysis analyzeAlert(AiAlertRequest request) {
        quotaService.checkAndIncrement(0L, "alert_analysis");

        Agent agent = null;
        if (request.getAgentId() != null) {
            agent = agentRepository.findById(request.getAgentId()).orElse(null);
        }

        String contextInfo = sensitiveDataFilter.filter(buildContextInfo(request, agent));

        try {
            ChatModel chatModel = chatModelFactory.getChatModel(null);
            String fullPrompt = ALERT_ANALYSIS_PROMPT + contextInfo;
            var response = chatModel.call(new Prompt(fullPrompt));
            String responseText = response.getResult().getOutput().getText();

            AiAlertAnalysis analysis = parseAnalysisResponse(responseText);

            if (analysis.isAutoFixAvailable() && analysis.getAutoFixScript() != null
                    && request.getAgentId() != null) {
                var riskAssessment = riskEngine.assessScript(analysis.getAutoFixScript());
                if (riskAssessment.getOverallRisk() == RiskLevel.LOW) {
                    AiExecutionRequest execRequest = new AiExecutionRequest();
                    execRequest.setScriptContent(analysis.getAutoFixScript());
                    execRequest.setAgentIds(List.of(request.getAgentId()));
                    execRequest.setDescription("[告警修复] " + request.getAlertDescription());
                    execRequest.setTimeoutSeconds(60);
                    execRequest.setUserId(0L);
                    execRequest.setSourceIp("alert-responder");

                    AiExecutionResult result = executionService.execute(execRequest);
                    log.info("Alert auto-fix executed: {}", result.getStatus());
                } else {
                    analysis = AiAlertAnalysis.builder()
                            .analysis(analysis.getAnalysis())
                            .suggestedAction(analysis.getSuggestedAction())
                            .riskLevel(analysis.getRiskLevel())
                            .autoFixAvailable(false)
                            .autoFixScript(analysis.getAutoFixScript() + "\n# 风险等级过高，已阻止自动执行")
                            .build();
                }
            }

            auditLogService.log(0L, "AI_ALERT",
                    "ALERT_ANALYSIS", "alert",
                    request.getAgentId(),
                    "告警分析: " + request.getAlertDescription(),
                    "alert-responder", "success");

            return analysis;
        } catch (Exception e) {
            log.error("Failed to analyze alert: {}", e.getMessage(), e);
            return AiAlertAnalysis.builder()
                    .analysis("AI 分析失败: " + e.getMessage())
                    .suggestedAction("请人工检查告警")
                    .riskLevel("UNKNOWN")
                    .autoFixAvailable(false)
                    .build();
        }
    }

    private String buildContextInfo(AiAlertRequest request, Agent agent) {
        StringBuilder sb = new StringBuilder();
        sb.append("告警描述: ").append(request.getAlertDescription()).append("\n");
        if (request.getSeverity() != null) {
            sb.append("严重程度: ").append(request.getSeverity()).append("\n");
        }
        if (request.getAlertSource() != null) {
            sb.append("告警来源: ").append(request.getAlertSource()).append("\n");
        }
        if (agent != null) {
            sb.append("主机信息: ").append(agent.getHostname())
                    .append(" (").append(agent.getIp()).append(")")
                    .append(", OS: ").append(agent.getOs())
                    .append(", CPU: ").append(agent.getCpuCores()).append("核")
                    .append(", 内存: ").append(agent.getMemTotal()).append("MB\n");
        }
        return sb.toString();
    }

    private AiAlertAnalysis parseAnalysisResponse(String responseText) {
        try {
            String cleaned = responseText;
            if (cleaned.contains("```json")) {
                cleaned = cleaned.substring(cleaned.indexOf("```json") + 7);
                cleaned = cleaned.substring(0, cleaned.indexOf("```"));
            } else if (cleaned.contains("```")) {
                cleaned = cleaned.substring(cleaned.indexOf("```") + 3);
                cleaned = cleaned.substring(0, cleaned.indexOf("```"));
            }
            cleaned = cleaned.trim();

            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = mapper.readTree(cleaned);

            return AiAlertAnalysis.builder()
                    .analysis(node.has("analysis") ? node.get("analysis").asText() : responseText)
                    .suggestedAction(node.has("suggestedAction") ? node.get("suggestedAction").asText() : "")
                    .riskLevel(node.has("riskLevel") ? node.get("riskLevel").asText() : "UNKNOWN")
                    .autoFixAvailable(node.has("autoFixAvailable") && node.get("autoFixAvailable").asBoolean())
                    .autoFixScript(node.has("autoFixScript") ? node.get("autoFixScript").asText() : null)
                    .build();
        } catch (Exception e) {
            log.warn("Failed to parse AI response as JSON, using raw text: {}", e.getMessage());
            return AiAlertAnalysis.builder()
                    .analysis(responseText)
                    .suggestedAction("")
                    .riskLevel("UNKNOWN")
                    .autoFixAvailable(false)
                    .build();
        }
    }
}
