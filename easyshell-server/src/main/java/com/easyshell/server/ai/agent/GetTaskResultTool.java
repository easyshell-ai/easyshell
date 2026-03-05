package com.easyshell.server.ai.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@RequiredArgsConstructor
public class GetTaskResultTool {

    private final BackgroundTaskManager backgroundTaskManager;

    /** Track poll attempts per taskId for exponential backoff: 5s, 10s, 20s, 40s... cap at 60s */
    private final ConcurrentHashMap<String, AtomicInteger> pollCounts = new ConcurrentHashMap<>();

    private static final long BASE_DELAY_MS = 5_000L;
    private static final long MAX_DELAY_MS = 60_000L;

    @Tool(description = "查询异步子任务的执行结果。使用 delegate_task 工具返回的 taskId 来查询。当任务仍在运行时可稍后重试。")
    public String getTaskResult(
            @ToolParam(description = "delegate_task 返回的任务ID") String taskId) {

        log.info("GetTaskResult invoked: taskId={}", taskId);

        BackgroundTask task = backgroundTaskManager.getTask(taskId);
        if (task == null) {
            pollCounts.remove(taskId);
            return "错误: 未找到任务ID '" + taskId + "'";
        }

        String status = task.getStatus();

        // Exponential backoff for running/pending tasks
        if ("running".equals(status) || "pending".equals(status)) {
            AtomicInteger counter = pollCounts.computeIfAbsent(taskId, k -> new AtomicInteger(0));
            int attempt = counter.incrementAndGet();
            long delayMs = Math.min(BASE_DELAY_MS * (1L << (attempt - 1)), MAX_DELAY_MS);
            log.info("Task {} still {}, backoff attempt={}, delay={}ms", taskId, status, attempt, delayMs);
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Backoff sleep interrupted for task {}", taskId);
            }
        } else {
            // Task completed/failed — clean up tracking
            pollCounts.remove(taskId);
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
