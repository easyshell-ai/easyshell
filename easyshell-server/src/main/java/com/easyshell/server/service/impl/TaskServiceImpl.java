package com.easyshell.server.service.impl;

import com.easyshell.server.ai.model.vo.RiskAssessment;
import com.easyshell.server.ai.risk.CommandRiskEngine;
import com.easyshell.server.ai.risk.RiskLevel;
import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.common.utils.ScriptParameterUtils;
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

    /**
     * job output 单列的最大保留字符数。
     * output 列是 MEDIUMTEXT（MySQL 约 16MB），取 256K 字符（utf8mb4 最坏约 1MB 字节）
     * 既足够留住长任务的尾部日志，又远离列上限，保证不会把列写溢出。
     */
    private static final int MAX_JOB_OUTPUT_LENGTH = 256 * 1024;

    private static final String NEWLINE = "\n";

    private final TaskRepository taskRepository;
    private final JobRepository jobRepository;
    private final ScriptRepository scriptRepository;
    private final AgentRepository agentRepository;
    private final TaskCounterService taskCounterService;
    private final TaskLogWebSocketHandler logWebSocketHandler;
    private final AgentWebSocketHandler agentWebSocketHandler;
    private final ClusterService clusterService;
    private final TagService tagService;
    private final CommandRiskEngine riskEngine;

    public TaskServiceImpl(
            TaskRepository taskRepository,
            JobRepository jobRepository,
            ScriptRepository scriptRepository,
            AgentRepository agentRepository,
            TaskCounterService taskCounterService,
            TaskLogWebSocketHandler logWebSocketHandler,
            @Lazy AgentWebSocketHandler agentWebSocketHandler,
            @Lazy ClusterService clusterService,
            @Lazy TagService tagService,
            CommandRiskEngine riskEngine) {
        this.taskRepository = taskRepository;
        this.jobRepository = jobRepository;
        this.scriptRepository = scriptRepository;
        this.agentRepository = agentRepository;
        this.taskCounterService = taskCounterService;
        this.logWebSocketHandler = logWebSocketHandler;
        this.agentWebSocketHandler = agentWebSocketHandler;
        this.clusterService = clusterService;
        this.tagService = tagService;
        this.riskEngine = riskEngine;
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

        if (request.getParameters() != null && !request.getParameters().isEmpty()) {
            Set<String> missing = ScriptParameterUtils.findMissingParameters(scriptContent, request.getParameters());
            if (!missing.isEmpty()) {
                throw new BusinessException(400, "Missing parameter values: " + String.join(", ", missing));
            }
            scriptContent = ScriptParameterUtils.substituteParameters(scriptContent, request.getParameters());
        }

        // 风险门禁下沉到唯一咽喉要道（B1-b）：无论调用方是 Controller、AI 工具、
        // 定时巡检还是未来的工作流，只要没显式带上"上游已授权"标记，替换后的完整
        // 脚本都要在这里过 CommandRiskEngine，非 LOW 一律拒绝派发。
        // 与 TaskController 的事前校验使用同一引擎、同一份（替换后）脚本，结果一致，
        // 已放行的 LOW 脚本二次校验不会行为变化（幂等）。
        if (!request.isRiskCleared()) {
            RiskAssessment risk = riskEngine.assessScript(scriptContent);
            if (risk.getOverallRisk() != RiskLevel.LOW) {
                log.warn("Blocked dispatch at chokepoint: risk={}, reason={}",
                        risk.getOverallRisk(), risk.getExplanation());
                throw new BusinessException(449, risk.getExplanation());
            }
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
                taskCounterService.applyJobResult(task.getId(), 3);
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
                taskCounterService.applyJobResult(task.getId(), 3);
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
                    // afterCommit 里外层事务已提交、线程上只剩一个用完的 EntityManager：
                    // 直接调仓库要么抛 "no transaction is in progress"（@Modifying），
                    // 要么 save 了却不 flush 静默丢失。必须走 REQUIRES_NEW 的独立事务落库。
                    taskCounterService.applyJobResultInNewTransaction(capturedJob, 3);
                }
            });
            dispatchedCount++;
        }

        // 这里不再统一 recalibrate 任务状态：上面每个失败 job 都已原子累加计数，
        // 最后一个失败 job 会把任务推到终态；有 job 正常派发时任务保持 running 等回传。

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
    public List<Task> listTasks(Long createdBy) {
        return taskRepository.findByCreatedByOrderByCreatedAtDesc(createdBy);
    }

    @Override
    public Page<Task> listTasks(Integer status, Pageable pageable) {
        if (status != null) {
            return taskRepository.findByStatusOrderByCreatedAtDesc(status, pageable);
        }
        return taskRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    @Override
    public Page<Task> listTasksByUser(Long createdBy, Pageable pageable) {
        return taskRepository.findByCreatedByOrderByCreatedAtDesc(createdBy, pageable);
    }

    @Override
    public Page<Task> listTasksByUser(Integer status, Long createdBy, Pageable pageable) {
        return taskRepository.findByStatusAndCreatedByOrderByCreatedAtDesc(status, createdBy, pageable);
    }

    @Override
    @Transactional
    public void reportJobResult(JobResultRequest request, String agentId) {
        Job job = jobRepository.findById(request.getJobId()).orElse(null);
        if (job == null) {
            log.warn("Ignoring result for unknown job {}: status={}, this may be a stale result from a reconnected agent",
                    request.getJobId(), request.getStatus());
            return;
        }

        if (agentId != null && !agentId.equals(job.getAgentId())) {
            log.warn("Ignoring result for job {} submitted by agent {} (job belongs to {})",
                    job.getId(), agentId, job.getAgentId());
            return;
        }

        // Skip updating if job is already in a terminal state (timeout/failed/success)
        if (job.getStatus() >= 2) {
            log.info("Ignoring result for job {} which is already in terminal state {}",
                    job.getId(), job.getStatus());
            return;
        }

        // 没有状态的结果没有意义：原子 UPDATE 需要明确的终态，否则无法决定计数归属，
        // 直接忽略，让 job 保持非终态交给超时看门狗收尾。
        if (request.getStatus() == null) {
            log.warn("Ignoring result for job {} without status", job.getId());
            return;
        }

        // 原子终态化 job：status < 2 是竞争条件，同一 job 被并发回传两次时只有第一个线程
        // 能改写该行（返回 1），后到的线程按返回值放弃，避免同一 job 被重复计数。
        int finalized = jobRepository.finalizeJobAtomically(
                job.getId(), request.getStatus(), request.getExitCode(), request.getOutput(),
                LocalDateTime.now());
        if (finalized == 0) {
            log.info("Ignoring result for job {} which was already finalized by a concurrent report",
                    job.getId());
            return;
        }

        taskCounterService.applyJobResult(job.getTaskId(), request.getStatus());
    }

    @Override
    @Transactional
    public void appendJobLog(String jobId, String logLine, String agentId) {
        Job job = jobRepository.findById(jobId).orElse(null);
        if (job == null) return;

        if (agentId != null && !agentId.equals(job.getAgentId())) {
            log.warn("Ignoring log for job {} submitted by agent {} (job belongs to {})",
                    jobId, agentId, job.getAgentId());
            return;
        }

        // 原子追加：把"读旧 output → 拼接 → 写回"压成一条 UPDATE，由数据库行锁串行化，
        // 同一 job 的并发日志行不会再互相覆盖丢失；超过 MAX_JOB_OUTPUT_LENGTH 时只保留尾部，
        // 保证 MEDIUMTEXT 列不会被写溢出。@Transactional 保证与后续 WebSocket 推送边界一致。
        int appended = jobRepository.appendOutputAtomically(jobId, logLine, NEWLINE, MAX_JOB_OUTPUT_LENGTH);
        if (appended == 0) {
            log.warn("Job {} disappeared while appending log, dropping line", jobId);
            return;
        }

        logWebSocketHandler.sendLog(job.getTaskId(), jobId, logLine);
    }

    @Override
    public List<Job> getAgentJobs(String agentId) {
        return jobRepository.findByAgentIdOrderByCreatedAtDesc(agentId);
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

                taskCounterService.applyJobResult(job.getTaskId(), 4);
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
            taskCounterService.applyJobResult(job.getTaskId(), 3);
            affectedTaskIds.add(job.getTaskId());
            log.warn("Job {} failed due to agent {} disconnect", job.getId(), agentId);
        }

        for (Job job : pendingJobs) {
            job.setStatus(3); // failed
            job.setOutput("[SYSTEM] " + reason);
            job.setFinishedAt(now);
            jobRepository.save(job);
            taskCounterService.applyJobResult(job.getTaskId(), 3);
            affectedTaskIds.add(job.getTaskId());
            log.warn("Pending job {} failed due to agent {} disconnect", job.getId(), agentId);
        }

        // 上面每个失败 job 都已原子累加计数，最后一个会把任务推到终态，无需再统一 recalibrate

        if (!affectedTaskIds.isEmpty()) {
            log.info("Agent {} disconnected: failed {} running + {} pending jobs across {} tasks",
                    agentId, runningJobs.size(), pendingJobs.size(), affectedTaskIds.size());
        }
    }
}
