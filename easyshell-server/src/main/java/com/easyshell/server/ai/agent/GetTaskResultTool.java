package com.easyshell.server.ai.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class GetTaskResultTool {

    private final BackgroundTaskManager backgroundTaskManager;

    @Tool(description = "查询异步子任务的执行结果。使用 delegate_task 工具返回的 taskId 来查询。当任务仍在运行时可稍后重试。")
    public String getTaskResult(
            @ToolParam(description = "delegate_task 返回的任务ID") String taskId) {

        log.info("GetTaskResult invoked: taskId={}", taskId);

        BackgroundTask task = backgroundTaskManager.getTask(taskId);
        if (task == null) {
            return "错误: 未找到任务ID '" + taskId + "'";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("任务ID: ").append(task.getTaskId()).append("\n");
        sb.append("Agent: ").append(task.getAgentName()).append("\n");
        sb.append("状态: ").append(task.getStatus()).append("\n");

        switch (task.getStatus()) {
            case "completed" -> {
                sb.append("完成时间: ").append(task.getCompletedAt()).append("\n");
                sb.append("结果:\n").append(task.getResult());
            }
            case "failed" -> {
                sb.append("完成时间: ").append(task.getCompletedAt()).append("\n");
                sb.append("错误: ").append(task.getError());
            }
            case "running" -> sb.append("任务正在执行中，请稍后再查询。");
            case "pending" -> sb.append("任务等待执行中，请稍后再查询。");
        }

        return sb.toString();
    }
}
