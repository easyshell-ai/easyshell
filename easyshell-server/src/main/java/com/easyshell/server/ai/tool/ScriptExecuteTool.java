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
import java.util.Map;

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

    @Tool(description = "在指定主机上执行 Shell 脚本。脚本会经过风险评估：低风险自动执行，中/高风险和封禁命令均需人工审批（用户确认后请调用 approveTask 工具）。注意：脚本中可能包含 {{variable_name}} 格式的参数占位符，执行前必须检查并通过 parameters 传入对应的值。")
    public String executeScript(
            @ToolParam(description = "要执行的 Shell 脚本内容") String scriptContent,
            @ToolParam(description = "目标主机 ID 列表。必须是真实存在的主机 Agent ID，如果上下文中用户已指定目标主机则直接使用其 ID，否则请先调用 listHosts 工具获取可用主机列表及其 ID，不要猜测或编造 ID") List<String> agentIds,
            @ToolParam(description = "脚本用途简述") String description,
            @ToolParam(description = "脚本参数键值对。如果脚本内容中包含 {{variable_name}} 格式的占位符，必须提供对应的参数值。例如：{\"server_ip\": \"192.168.1.1\", \"port\": \"8080\"}。如果脚本不含参数占位符，传 null 即可", required = false) Map<String, String> parameters) {

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
        request.setParameters(parameters);

        AiExecutionResult result = aiExecutionService.execute(request);

        return switch (result.getStatus()) {
            case "executed" -> "脚本已自动执行，任务ID: " + result.getTaskId();
            case "pending_approval" -> {
                String riskPrefix = result.getMessage() != null && result.getMessage().contains("封禁")
                        ? "脚本包含封禁命令，已提交待管理员审批。"
                        : "脚本包含风险命令，已提交待人工审批。";
                yield riskPrefix + "任务ID: " + result.getTaskId() + "\n原因: " + result.getMessage()
                        + "\n请告知用户可以通过审批页面或调用 approveTask 工具来审批执行。";
            }
            case "rejected" -> "脚本被拒绝执行。\n原因: " + result.getMessage();
            default -> "执行状态: " + result.getStatus() + " - " + result.getMessage();
        };
    }
}
