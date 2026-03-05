package com.easyshell.server.service.impl;

import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.dto.JobResultRequest;
import com.easyshell.server.model.dto.TaskCreateRequest;
import com.easyshell.server.model.entity.Agent;
import com.easyshell.server.model.entity.Job;
import com.easyshell.server.model.entity.Script;
import com.easyshell.server.model.entity.Task;
import com.easyshell.server.model.vo.TaskDetailVO;
import com.easyshell.server.repository.AgentRepository;
import com.easyshell.server.repository.JobRepository;
import com.easyshell.server.repository.ScriptRepository;
import com.easyshell.server.repository.TaskRepository;
import com.easyshell.server.service.ClusterService;
import com.easyshell.server.service.TagService;
import com.easyshell.server.service.TaskService;
import com.easyshell.server.websocket.AgentWebSocketHandler;
import com.easyshell.server.websocket.TaskLogWebSocketHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class TaskServiceImpl implements TaskService {

    private final TaskRepository taskRepository;
    private final JobRepository jobRepository;
    private final ScriptRepository scriptRepository;
    private final AgentRepository agentRepository;
    private final TaskLogWebSocketHandler logWebSocketHandler;
    private final AgentWebSocketHandler agentWebSocketHandler;
    private final ClusterService clusterService;
    private final TagService tagService;

    public TaskServiceImpl(
            TaskRepository taskRepository,
            JobRepository jobRepository,
            ScriptRepository scriptRepository,
            AgentRepository agentRepository,
            TaskLogWebSocketHandler logWebSocketHandler,
            @Lazy AgentWebSocketHandler agentWebSocketHandler,
            @Lazy ClusterService clusterService,
            @Lazy TagService tagService) {
        this.taskRepository = taskRepository;
        this.jobRepository = jobRepository;
        this.scriptRepository = scriptRepository;
        this.agentRepository = agentRepository;
        this.logWebSocketHandler = logWebSocketHandler;
        this.agentWebSocketHandler = agentWebSocketHandler;
        this.clusterService = clusterService;
        this.tagService = tagService;
    }

    @Override
    @Transactional
    public Task createAndDispatch(TaskCreateRequest request, Long userId) {
        String scriptContent = request.getScriptContent();
        String scriptType = "shell";

        if (request.getScriptId() != null) {
            Script script = scriptRepository.findById(request.getScriptId())
                    .orElseThrow(() -> new BusinessException(404, "Script not found"));
            scriptContent = script.getContent();
            scriptType = script.getScriptType();
        }

        if (scriptContent == null || scriptContent.isBlank()) {
            throw new BusinessException(400, "Script content is required");
        }

        Set<String> resolvedAgentIds = new LinkedHashSet<>();
        if (request.getAgentIds() != null) {
            resolvedAgentIds.addAll(request.getAgentIds());
        }
        if (request.getClusterIds() != null && !request.getClusterIds().isEmpty()) {
            resolvedAgentIds.addAll(clusterService.getAgentIdsByClusterIds(request.getClusterIds()));
        }
        if (request.getTagIds() != null && !request.getTagIds().isEmpty()) {
            resolvedAgentIds.addAll(tagService.getAgentIdsByTagIds(request.getTagIds()));
        }

        if (resolvedAgentIds.isEmpty()) {
            throw new BusinessException(400, "At least one agent must be specified (via agentIds, clusterIds, or tagIds)");
        }

        Task task = new Task();
        task.setId("task_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        task.setName(request.getName());
        task.setScriptId(request.getScriptId());
        task.setScriptContent(scriptContent);
        task.setScriptType(scriptType);
        task.setTimeoutSeconds(request.getTimeoutSeconds());
        task.setStatus(1);
        task.setTotalCount(resolvedAgentIds.size());
        task.setSuccessCount(0);
        task.setFailedCount(0);
        task.setCreatedBy(userId);
        task.setStartedAt(LocalDateTime.now());
        taskRepository.save(task);

        // Collect dispatch actions to execute AFTER transaction commits
        // This prevents the race condition where fast agents return results
        // before the creating transaction is visible to other threads
        List<Runnable> postCommitDispatches = new ArrayList<>();

        int dispatchedCount = 0;
        for (String agentId : resolvedAgentIds) {
            Agent agent = agentRepository.findById(agentId).orElse(null);
            if (agent == null) {
                log.warn("Skipping unknown agent: {}", agentId);
                Job failedJob = new Job();
                failedJob.setId("job_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
                failedJob.setTaskId(task.getId());
                failedJob.setAgentId(agentId);
                failedJob.setStatus(3); // failed
                failedJob.setOutput("Agent not found");
                failedJob.setFinishedAt(LocalDateTime.now());
                jobRepository.save(failedJob);
                continue;
            }
            // Check both DB status AND WebSocket connectivity.
            // Agent may be marked offline by heartbeat monitor but still have active WebSocket.
            if (agent.getStatus() == 0 && !agentWebSocketHandler.isAgentConnected(agentId)) {
                log.warn("Skipping offline agent (no DB status, no WebSocket): {}", agentId);
                Job failedJob = new Job();
                failedJob.setId("job_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
                failedJob.setTaskId(task.getId());
                failedJob.setAgentId(agentId);
                failedJob.setStatus(3); // failed
                failedJob.setOutput("Agent is offline or not found");
                failedJob.setFinishedAt(LocalDateTime.now());
                jobRepository.save(failedJob);
                continue;
            }

            Job job = new Job();
            job.setId("job_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
            job.setTaskId(task.getId());
            job.setAgentId(agentId);
            job.setStatus(0);
            jobRepository.save(job);

            // Capture variables for lambda
            final String capturedAgentId = agentId;
            final Job capturedJob = job;
            final String capturedScript = scriptContent;
            final Integer capturedTimeout = task.getTimeoutSeconds();

            postCommitDispatches.add(() -> {
                boolean dispatched = agentWebSocketHandler.dispatchJob(
                        capturedAgentId, capturedJob, capturedScript, capturedTimeout);
                if (!dispatched) {
                    log.warn("Agent {} not connected via WebSocket, marking job {} as failed",
                            capturedAgentId, capturedJob.getId());
                    capturedJob.setStatus(3); // failed
                    capturedJob.setOutput("Agent not connected via WebSocket, unable to dispatch");
                    capturedJob.setFinishedAt(LocalDateTime.now());
                    jobRepository.save(capturedJob);
                    updateTaskStatus(capturedJob.getTaskId());
                }
            });
            dispatchedCount++;
        }

        // Update task status if no jobs were dispatched
        updateTaskStatus(task.getId());

        // Register post-commit hook: dispatch WebSocket messages only AFTER
        // the transaction commits, so agent results can find the job rows in DB
        if (!postCommitDispatches.isEmpty()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    log.info("Transaction committed for task {}, dispatching {} jobs to agents",
                            task.getId(), postCommitDispatches.size());
                    for (Runnable dispatch : postCommitDispatches) {
                        try {
                            dispatch.run();
                        } catch (Exception e) {
                            log.error("Failed to dispatch job after commit: {}", e.getMessage(), e);
                        }
                    }
                }
            });
        }

        return task;
    }

    @Override
    public TaskDetailVO getTaskDetail(String taskId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(404, "Task not found"));
        List<Job> jobs = jobRepository.findByTaskId(taskId);
        return TaskDetailVO.builder().task(task).jobs(jobs).build();
    }

    @Override
    public List<Task> listTasks() {
        return taskRepository.findAllByOrderByCreatedAtDesc();
    }

    @Override
    public Page<Task> listTasks(Integer status, Pageable pageable) {
        if (status != null) {
            return taskRepository.findByStatusOrderByCreatedAtDesc(status, pageable);
        }
        return taskRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    @Override
    @Transactional
    public void reportJobResult(JobResultRequest request) {
        Job job = jobRepository.findById(request.getJobId()).orElse(null);
        if (job == null) {
            log.warn("Ignoring result for unknown job {}: status={}, this may be a stale result from a reconnected agent",
                    request.getJobId(), request.getStatus());
            return;
        }

        // Skip updating if job is already in a terminal state (timeout/failed/success)
        if (job.getStatus() >= 2) {
            log.info("Ignoring result for job {} which is already in terminal state {}",
                    job.getId(), job.getStatus());
            return;
        }

        job.setStatus(request.getStatus());
        job.setExitCode(request.getExitCode());
        job.setOutput(request.getOutput());
        job.setFinishedAt(LocalDateTime.now());
        jobRepository.save(job);

        updateTaskStatus(job.getTaskId());
    }

    @Override
    public void appendJobLog(String jobId, String logLine) {
        Job job = jobRepository.findById(jobId).orElse(null);
        if (job == null) return;

        String currentOutput = job.getOutput();
        if (currentOutput == null || currentOutput.isEmpty()) {
            job.setOutput(logLine);
        } else {
            job.setOutput(currentOutput + "\n" + logLine);
        }
        jobRepository.save(job);

        logWebSocketHandler.sendLog(job.getTaskId(), jobId, logLine);
    }

    @Override
    public List<Job> getAgentJobs(String agentId) {
        return jobRepository.findByAgentIdOrderByCreatedAtDesc(agentId);
    }

    private void updateTaskStatus(String taskId) {
        List<Job> jobs = jobRepository.findByTaskId(taskId);
        long total = jobs.size();
        long success = jobs.stream().filter(j -> j.getStatus() == 2).count();
        long failed = jobs.stream().filter(j -> j.getStatus() == 3 || j.getStatus() == 4).count();
        long completed = success + failed;

        Task task = taskRepository.findById(taskId).orElse(null);
        if (task == null) return;

        task.setSuccessCount((int) success);
        task.setFailedCount((int) failed);

        if (completed == total) {
            if (failed == 0) {
                task.setStatus(2);
            } else if (success == 0) {
                task.setStatus(4);
            } else {
                task.setStatus(3);
            }
            task.setFinishedAt(LocalDateTime.now());
        }

        taskRepository.save(task);
    }

    @Override
    @Transactional
    public void timeoutStaleJobs() {
        // Find all running jobs (status=1)
        List<Job> runningJobs = jobRepository.findByStatus(1);
        LocalDateTime now = LocalDateTime.now();
        int timedOutCount = 0;

        for (Job job : runningJobs) {
            if (job.getStartedAt() == null) continue;

            // Get the task's timeout setting
            Task task = taskRepository.findById(job.getTaskId()).orElse(null);
            if (task == null) continue;

            int timeoutSeconds = task.getTimeoutSeconds() != null ? task.getTimeoutSeconds() : 3600;
            LocalDateTime deadline = job.getStartedAt().plusSeconds(timeoutSeconds);

            if (now.isAfter(deadline)) {
                job.setStatus(4); // timeout
                job.setOutput((job.getOutput() != null ? job.getOutput() + "\n" : "") +
                        "[SYSTEM] Job timed out after " + timeoutSeconds + " seconds");
                job.setFinishedAt(now);
                jobRepository.save(job);
                timedOutCount++;
                log.warn("Job {} on agent {} timed out (started={}, timeout={}s)",
                        job.getId(), job.getAgentId(), job.getStartedAt(), timeoutSeconds);

                updateTaskStatus(job.getTaskId());
            }
        }

        if (timedOutCount > 0) {
            log.info("Timeout watchdog: marked {} stale jobs as timed out", timedOutCount);
        }
    }

    @Override
    @Transactional
    public void failRunningJobsForAgent(String agentId, String reason) {
        List<Job> runningJobs = jobRepository.findByAgentIdAndStatus(agentId, 1);
        List<Job> pendingJobs = jobRepository.findByAgentIdAndStatus(agentId, 0);

        LocalDateTime now = LocalDateTime.now();
        Set<String> affectedTaskIds = new java.util.HashSet<>();

        for (Job job : runningJobs) {
            job.setStatus(3); // failed
            job.setOutput((job.getOutput() != null ? job.getOutput() + "\n" : "") +
                    "[SYSTEM] " + reason);
            job.setFinishedAt(now);
            jobRepository.save(job);
            affectedTaskIds.add(job.getTaskId());
            log.warn("Job {} failed due to agent {} disconnect", job.getId(), agentId);
        }

        for (Job job : pendingJobs) {
            job.setStatus(3); // failed
            job.setOutput("[SYSTEM] " + reason);
            job.setFinishedAt(now);
            jobRepository.save(job);
            affectedTaskIds.add(job.getTaskId());
            log.warn("Pending job {} failed due to agent {} disconnect", job.getId(), agentId);
        }

        for (String taskId : affectedTaskIds) {
            updateTaskStatus(taskId);
        }

        if (!affectedTaskIds.isEmpty()) {
            log.info("Agent {} disconnected: failed {} running + {} pending jobs across {} tasks",
                    agentId, runningJobs.size(), pendingJobs.size(), affectedTaskIds.size());
        }
    }
}
