package com.easyshell.server.ai.tool;

import com.easyshell.server.ai.model.entity.AiInspectReport;
import com.easyshell.server.ai.model.entity.AiScheduledTask;
import com.easyshell.server.ai.repository.AiInspectReportRepository;
import com.easyshell.server.ai.repository.AiScheduledTaskRepository;
import com.easyshell.server.ai.service.AiSchedulerService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ScheduledTaskTool {

    private final AiScheduledTaskRepository scheduledTaskRepository;
    private final AiInspectReportRepository reportRepository;
    private final AiSchedulerService schedulerService;

    @Tool(description = "查询所有定时巡检任务列表，包括名称、Cron 表达式、启用状态、上次执行时间等")
    public String listScheduledTasks() {
        List<AiScheduledTask> tasks = scheduledTaskRepository.findAllByOrderByCreatedAtDesc();
        if (tasks.isEmpty()) {
            return "当前没有配置任何定时巡检任务";
        }

        StringBuilder sb = new StringBuilder("定时巡检任务列表:\n");
        for (AiScheduledTask t : tasks) {
            sb.append(String.format("- [ID:%d] %s | 类型: %s | Cron: %s | 状态: %s | 上次执行: %s\n",
                    t.getId(),
                    t.getName(),
                    t.getTaskType(),
                    t.getCronExpression(),
                    t.getEnabled() ? "启用" : "禁用",
                    t.getLastRunAt() != null ? t.getLastRunAt().toString() : "从未执行"));
        }
        return sb.toString();
    }

    @Tool(description = "查询最近的巡检报告列表，包括任务名称、状态、AI 分析摘要等")
    public String getInspectReports(@ToolParam(description = "返回条数，默认 10") int limit) {
        if (limit <= 0) limit = 10;
        if (limit > 50) limit = 50;

        Page<AiInspectReport> page = reportRepository.findAllByOrderByCreatedAtDesc(
                PageRequest.of(0, limit));

        if (page.isEmpty()) {
            return "暂无巡检报告";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("巡检报告 (共 %d 条，显示 %d 条):\n\n", page.getTotalElements(), page.getContent().size()));

        for (AiInspectReport r : page.getContent()) {
            sb.append(String.format("[ID:%d] %s | 类型: %s | 状态: %s | 时间: %s\n",
                    r.getId(),
                    r.getTaskName(),
                    r.getTaskType(),
                    r.getStatus(),
                    r.getCreatedAt()));
            if (r.getAiAnalysis() != null && !r.getAiAnalysis().isBlank()) {
                String analysis = r.getAiAnalysis().length() > 200
                        ? r.getAiAnalysis().substring(0, 200) + "..."
                        : r.getAiAnalysis();
                sb.append("  AI 分析摘要: ").append(analysis).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    @Tool(description = "手动触发指定的定时巡检任务立即执行")
    public String triggerScheduledTask(@ToolParam(description = "定时任务 ID") Long id) {
        try {
            AiScheduledTask task = scheduledTaskRepository.findById(id).orElse(null);
            if (task == null) {
                return "未找到 ID 为 " + id + " 的定时任务";
            }
            schedulerService.executeScheduledTaskAsync(id);
            return String.format("已触发定时任务 [%s] (ID:%d) 立即执行，请稍后查看巡检报告。", task.getName(), id);
        } catch (Exception e) {
            return "触发定时任务失败: " + e.getMessage();
        }
    }
}
