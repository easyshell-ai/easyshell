package com.easyshell.server.ai.tool;

import com.easyshell.server.ai.model.dto.AiExecutionRequest;
import com.easyshell.server.ai.model.vo.AiExecutionResult;
import com.easyshell.server.ai.service.AiExecutionService;
import com.easyshell.server.repository.AgentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ScriptExecuteTool {

    private final AiExecutionService aiExecutionService;
    private final AgentRepository agentRepository;

    private Long currentUserId;
    private String currentSourceIp;

    public void setContext(Long userId, String sourceIp) {
        this.currentUserId = userId;
        this.currentSourceIp = sourceIp;
    }

    @Tool(description = "在指定主机上执行 Shell 脚本。脚本会经过风险评估：低风险自动执行，中/高风险需人工确认（用户确认后请调用 approveTask 工具），封禁命令将被拒绝。")
    public String executeScript(
            @ToolParam(description = "要执行的 Shell 脚本内容") String scriptContent,
            @ToolParam(description = "目标主机 ID 列表。必须是真实存在的主机 Agent ID，如果上下文中用户已指定目标主机则直接使用其 ID，否则请先调用 listHosts 工具获取可用主机列表及其 ID，不要猜测或编造 ID") List<String> agentIds,
            @ToolParam(description = "脚本用途简述") String description) {

        // Validate all agentIds exist before executing
        if (agentIds == null || agentIds.isEmpty()) {
            return "错误：未指定目标主机。请先调用 listHosts 工具获取可用主机列表。";
        }
        List<String> invalidIds = agentIds.stream()
                .filter(id -> !agentRepository.existsById(id.trim()))
                .toList();
        if (!invalidIds.isEmpty()) {
            return "错误：以下主机 ID 不存在: " + String.join(", ", invalidIds) + "。请调用 listHosts 工具获取正确的主机 ID。";
        }

        AiExecutionRequest request = new AiExecutionRequest();
        request.setScriptContent(scriptContent);
        request.setAgentIds(agentIds);
        request.setDescription(description);
        request.setTimeoutSeconds(60);
        request.setUserId(currentUserId != null ? currentUserId : 0L);
        request.setSourceIp(currentSourceIp != null ? currentSourceIp : "ai-chat");

        AiExecutionResult result = aiExecutionService.execute(request);

        return switch (result.getStatus()) {
            case "executed" -> "脚本已自动执行，任务ID: " + result.getTaskId();
            case "pending_approval" -> "脚本包含中风险命令，已提交待人工审批。任务ID: " + result.getTaskId() + "\n原因: " + result.getMessage();
            case "rejected" -> "脚本被拒绝执行。\n原因: " + result.getMessage();
            default -> "执行状态: " + result.getStatus() + " - " + result.getMessage();
        };
    }
}
