package com.easyshell.server.ai.tool;

import com.easyshell.server.ai.service.AiExecutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ApprovalTool {

    private final AiExecutionService aiExecutionService;

    private Long currentUserId;

    public void setContext(Long userId) {
        this.currentUserId = userId;
    }

    @Tool(description = "审批待执行的任务。当脚本被风险管控拦截后（中风险或高风险），用户确认可以执行时，调用此工具批准执行。仅在用户明确表示同意/确认/允许执行时才调用。")
    public String approveTask(
            @ToolParam(description = "待审批的任务ID，格式如 task_xxxx") String taskId) {
        try {
            Long userId = currentUserId != null ? currentUserId : 0L;
            aiExecutionService.approveExecution(taskId, userId);
            return "任务 " + taskId + " 已审批通过并开始执行。";
        } catch (Exception e) {
            return "审批失败: " + e.getMessage();
        }
    }

    @Tool(description = "拒绝待执行的任务。当脚本被风险管控拦截后，用户明确表示不执行时，调用此工具拒绝执行。")
    public String rejectTask(
            @ToolParam(description = "待拒绝的任务ID，格式如 task_xxxx") String taskId) {
        try {
            Long userId = currentUserId != null ? currentUserId : 0L;
            aiExecutionService.rejectExecution(taskId, userId);
            return "任务 " + taskId + " 已拒绝执行。";
        } catch (Exception e) {
            return "拒绝操作失败: " + e.getMessage();
        }
    }
}
