package com.easyshell.server.ai.tool;

import com.easyshell.server.model.dto.TaskCreateRequest;
import com.easyshell.server.model.entity.Job;
import com.easyshell.server.model.entity.Task;
import com.easyshell.server.model.vo.TaskDetailVO;
import com.easyshell.server.service.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class TaskManageTool {

    private final TaskService taskService;

    @Tool(description = "查询最近的任务列表，包括任务名称、状态、创建时间等信息")
    public String listRecentTasks() {
        List<Task> tasks = taskService.listTasks();
        if (tasks.isEmpty()) {
            return "当前没有任何任务记录";
        }

        StringBuilder sb = new StringBuilder("最近任务列表:\n");
        int limit = Math.min(tasks.size(), 20);
        for (int i = 0; i < limit; i++) {
            Task t = tasks.get(i);
            sb.append(String.format("- [%s] %s | 状态: %s | 创建: %s\n",
                    t.getId(),
                    t.getName(),
                    formatTaskStatus(t.getStatus()),
                    t.getCreatedAt()));
        }
        if (tasks.size() > 20) {
            sb.append("...(共 ").append(tasks.size()).append(" 条，仅显示最近 20 条)\n");
        }
        return sb.toString();
    }

    @Tool(description = "查询指定任务的详细信息，包括任务下发到各主机的执行结果和输出")
    public String getTaskDetail(@ToolParam(description = "任务 ID") String taskId) {
        try {
            TaskDetailVO detail = taskService.getTaskDetail(taskId);
            Task task = detail.getTask();
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("任务: %s\nID: %s\n状态: %s\n创建时间: %s\n\n",
                    task.getName(), task.getId(),
                    formatTaskStatus(task.getStatus()),
                    task.getCreatedAt()));

            List<Job> jobs = detail.getJobs();
            if (jobs != null && !jobs.isEmpty()) {
                sb.append("执行结果:\n");
                for (Job job : jobs) {
                    sb.append(String.format("  Agent %s: 状态=%s, 退出码=%s\n",
                            job.getAgentId(),
                            formatJobStatus(job.getStatus()),
                            job.getExitCode()));
                    if (job.getOutput() != null && !job.getOutput().isBlank()) {
                        String output = job.getOutput().length() > 500
                                ? job.getOutput().substring(0, 500) + "...(已截断)"
                                : job.getOutput();
                        sb.append("    输出: ").append(output).append("\n");
                    }
                }
            } else {
                sb.append("暂无执行结果\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "查询任务详情失败: " + e.getMessage();
        }
    }

    @Tool(description = "创建并下发一个新的脚本执行任务到指定主机")
    public String createTask(
            @ToolParam(description = "脚本内容") String scriptContent,
            @ToolParam(description = "目标主机 ID 列表，用逗号分隔") String agentIds,
            @ToolParam(description = "任务名称") String name,
            @ToolParam(description = "超时秒数，默认 3600") int timeoutSeconds) {
        try {
            TaskCreateRequest request = new TaskCreateRequest();
            request.setName(name);
            request.setScriptContent(scriptContent);
            request.setAgentIds(List.of(agentIds.split(",")));
            request.setTimeoutSeconds(timeoutSeconds > 0 ? timeoutSeconds : 3600);

            Task task = taskService.createAndDispatch(request, 1L);
            return String.format("任务创建成功！任务ID: %s, 名称: %s, 目标主机数: %d",
                    task.getId(), task.getName(), request.getAgentIds().size());
        } catch (Exception e) {
            return "创建任务失败: " + e.getMessage();
        }
    }

    private String formatTaskStatus(Integer status) {
        if (status == null) return "未知";
        return switch (status) {
            case 0 -> "待执行";
            case 1 -> "执行中";
            case 2 -> "已完成";
            case 3 -> "失败";
            default -> "未知(" + status + ")";
        };
    }

    private String formatJobStatus(Integer status) {
        if (status == null) return "未知";
        return switch (status) {
            case 0 -> "待执行";
            case 1 -> "执行中";
            case 2 -> "成功";
            case 3 -> "失败";
            case 4 -> "超时";
            default -> "未知(" + status + ")";
        };
    }
}
