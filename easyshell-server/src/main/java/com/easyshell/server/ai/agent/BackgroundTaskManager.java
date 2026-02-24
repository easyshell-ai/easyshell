package com.easyshell.server.ai.agent;

import com.easyshell.server.ai.config.AgenticConfigService;
import com.easyshell.server.ai.orchestrator.OrchestratorEngine;
import com.easyshell.server.ai.service.ChatModelFactory;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

@Slf4j
@Service
public class BackgroundTaskManager {

    private final ConcurrentHashMap<String, BackgroundTask> tasks = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private final ScheduledExecutorService scheduler;
    private final int defaultTimeoutSec;

    public BackgroundTaskManager(AgenticConfigService agenticConfigService) {
        int poolSize = agenticConfigService.getInt("ai.agent.background-pool-size", 5);
        this.executor = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "sub-agent-worker");
            t.setDaemon(true);
            return t;
        });
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "task-timeout-scheduler");
            t.setDaemon(true);
            return t;
        });
        this.defaultTimeoutSec = agenticConfigService.getInt("ai.agent.task-timeout-sec", 120);
        log.info("BackgroundTaskManager initialized with pool size {}, default timeout {}s", poolSize, defaultTimeoutSec);
    }

    public String submit(AgentDefinition agent, String prompt,
                         OrchestratorEngine engine, ChatModelFactory chatModelFactory) {
        return submit(agent, prompt, engine, chatModelFactory, defaultTimeoutSec);
    }

    public String submit(AgentDefinition agent, String prompt,
                         OrchestratorEngine engine, ChatModelFactory chatModelFactory, int timeoutSec) {
        String taskId = "task_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        BackgroundTask task = new BackgroundTask(taskId, agent.getName());
        tasks.put(taskId, task);

        Future<?> future = executor.submit(() -> {
            task.setStatus("running");
            task.setStartedAt(Instant.now());
            try {
                String result = engine.executeAsSubAgent(agent, prompt, chatModelFactory);
                task.setResult(result);
                task.setStatus("completed");
                task.setCompletedAt(Instant.now());
                task.setDurationMs(task.getCompletedAt().toEpochMilli() - task.getStartedAt().toEpochMilli());
                log.info("Background task {} ({}) completed in {}ms", taskId, agent.getName(), task.getDurationMs());
            } catch (Exception e) {
                task.setError(e.getMessage());
                task.setStatus("failed");
                task.setCompletedAt(Instant.now());
                task.setDurationMs(task.getCompletedAt().toEpochMilli() - task.getStartedAt().toEpochMilli());
                log.error("Background task {} ({}) failed", taskId, agent.getName(), e);
            }
        });

        if (timeoutSec > 0) {
            scheduler.schedule(() -> {
                if (!"completed".equals(task.getStatus()) && !"failed".equals(task.getStatus())) {
                    future.cancel(true);
                    task.setStatus("failed");
                    task.setError("Task timed out after " + timeoutSec + "s");
                    task.setCompletedAt(Instant.now());
                    if (task.getStartedAt() != null) {
                        task.setDurationMs(task.getCompletedAt().toEpochMilli() - task.getStartedAt().toEpochMilli());
                    }
                    log.warn("Background task {} ({}) timed out after {}s", taskId, agent.getName(), timeoutSec);
                }
            }, timeoutSec, TimeUnit.SECONDS);
        }

        return taskId;
    }

    public List<BackgroundTask> submitBatchAndWait(List<BatchTaskRequest> requests,
                                                    OrchestratorEngine engine,
                                                    ChatModelFactory chatModelFactory) {
        List<String> taskIds = new ArrayList<>();
        for (BatchTaskRequest req : requests) {
            String taskId = submit(req.getAgentDefinition(), req.getPrompt(), engine, chatModelFactory,
                    req.getTimeoutSec() > 0 ? req.getTimeoutSec() : defaultTimeoutSec);
            BackgroundTask task = tasks.get(taskId);
            if (task != null) {
                task.setHostId(req.getHostId());
                task.setStepIndex(req.getStepIndex());
            }
            taskIds.add(taskId);
        }

        long deadline = System.currentTimeMillis() + (long) defaultTimeoutSec * 1000 * 2;
        for (String taskId : taskIds) {
            BackgroundTask task = tasks.get(taskId);
            if (task == null) continue;
            while (!"completed".equals(task.getStatus()) && !"failed".equals(task.getStatus())) {
                if (System.currentTimeMillis() > deadline) {
                    task.setStatus("failed");
                    task.setError("Batch wait deadline exceeded");
                    break;
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        List<BackgroundTask> results = new ArrayList<>();
        for (String taskId : taskIds) {
            BackgroundTask task = tasks.get(taskId);
            if (task != null) results.add(task);
        }
        return results;
    }

    public boolean cancelTask(String taskId) {
        BackgroundTask task = tasks.get(taskId);
        if (task == null) return false;
        if ("completed".equals(task.getStatus()) || "failed".equals(task.getStatus())) return false;
        task.setStatus("failed");
        task.setError("Cancelled by user");
        task.setCompletedAt(Instant.now());
        if (task.getStartedAt() != null) {
            task.setDurationMs(task.getCompletedAt().toEpochMilli() - task.getStartedAt().toEpochMilli());
        }
        log.info("Background task {} cancelled", taskId);
        return true;
    }

    public String aggregateResults(List<BackgroundTask> completedTasks) {
        StringBuilder sb = new StringBuilder();
        for (BackgroundTask task : completedTasks) {
            sb.append(String.format("[%s] %s: %s\n",
                    task.getAgentName(),
                    task.getStatus(),
                    "completed".equals(task.getStatus()) ? task.getResult() : task.getError()));
        }
        return sb.toString();
    }

    public BackgroundTask getTask(String taskId) {
        return tasks.get(taskId);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        scheduler.shutdown();
        log.info("BackgroundTaskManager shutdown");
    }
}
