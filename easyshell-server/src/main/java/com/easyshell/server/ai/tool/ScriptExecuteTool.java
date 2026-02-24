package com.easyshell.server.ai.tool;

import com.easyshell.server.ai.model.dto.AiExecutionRequest;
import com.easyshell.server.ai.model.vo.AiExecutionResult;
import com.easyshell.server.ai.service.AiExecutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ScriptExecuteTool {

    private final AiExecutionService aiExecutionService;

    private Long currentUserId;
    private String currentSourceIp;

    public void setContext(Long userId, String sourceIp) {
        this.currentUserId = userId;
        this.currentSourceIp = sourceIp;
    }

    @Tool(description = "在指定主机上执行 Shell 脚本。脚本会经过风险评估：低风险自动执行，中/高风险需人工确认（用户确认后请调用 approveTask 工具），封禁命令将被拒绝。")
    public String executeScript(
            @ToolParam(description = "要执行的 Shell 脚本内容") String scriptContent,
            @ToolParam(description = "目标主机 ID 列表") List<String> agentIds,
            @ToolParam(description = "脚本用途简述") String description) {

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
