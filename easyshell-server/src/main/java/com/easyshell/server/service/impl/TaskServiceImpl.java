package com.easyshell.server.service.impl;

import com.easyshell.server.ai.model.vo.RiskAssessment;
import com.easyshell.server.ai.risk.CommandRiskEngine;
import com.easyshell.server.ai.risk.RiskLevel;
import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.common.utils.ScriptParameterUtils;
import com.easyshell.server.model.dto.JobResultRequest;
import com.easyshell.server.model.dto.TaskCreateRequest;
import com.easyshell.server.model.dto.TaskTargetSnapshot;
import com.easyshell.server.model.entity.Agent;
import com.easyshell.server.model.entity.Job;
import com.easyshell.server.model.entity.Script;
import com.easyshell.server.model.entity.Task;
import com.easyshell.server.model.vo.TaskDetailVO;
import com.easyshell.server.repository.AgentRepository;
import com.easyshell.server.repository.JobRepository;
import com.easyshell.server.security.ScriptAccess;
import com.easyshell.server.repository.TaskRepository;
import com.easyshell.server.repository.TaskTargetSnapshotRepository;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.service.ClusterService;
import com.easyshell.server.service.DispatchAuthority;
import com.easyshell.server.service.DispatchEvidenceVerifier;
import com.easyshell.server.service.HostAclService;
import com.easyshell.server.service.HostIdentityService;
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
import java.util.Objects;
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
    private final ScriptAccess scriptAccess;
    private final AgentRepository agentRepository;
    private final TaskCounterService taskCounterService;
    private final TaskLogWebSocketHandler logWebSocketHandler;
    private final AgentWebSocketHandler agentWebSocketHandler;
    private final ClusterService clusterService;
    private final TagService tagService;
    private final CommandRiskEngine riskEngine;
    private final AuditLogService auditLogService;
    private final HostAclService hostAclService;
    private final HostIdentityService hostIdentityService;
    private final TaskTargetSnapshotRepository targetSnapshots;
    /** 授权证据校验器（按 supports() 分派）；没有校验器的授权值一律 fail-closed */
    private final List<DispatchEvidenceVerifier> evidenceVerifiers;

    public TaskServiceImpl(
            TaskRepository taskRepository,
            JobRepository jobRepository,
            ScriptAccess scriptAccess,
            AgentRepository agentRepository,
            TaskCounterService taskCounterService,
            TaskLogWebSocketHandler logWebSocketHandler,
            @Lazy AgentWebSocketHandler agentWebSocketHandler,
            @Lazy ClusterService clusterService,
            @Lazy TagService tagService,
            CommandRiskEngine riskEngine,
            AuditLogService auditLogService,
            List<DispatchEvidenceVerifier> evidenceVerifiers, HostAclService hostAclService,
            HostIdentityService hostIdentityService, TaskTargetSnapshotRepository targetSnapshots) {
        this.taskRepository = taskRepository;
        this.jobRepository = jobRepository;
        this.scriptAccess = scriptAccess;
        this.agentRepository = agentRepository;
        this.taskCounterService = taskCounterService;
        this.logWebSocketHandler = logWebSocketHandler;
        this.agentWebSocketHandler = agentWebSocketHandler;
        this.clusterService = clusterService;
        this.tagService = tagService;
        this.riskEngine = riskEngine;
        this.auditLogService = auditLogService;
        this.evidenceVerifiers = evidenceVerifiers;
        this.hostAclService = hostAclService;
        this.hostIdentityService = hostIdentityService;
        this.targetSnapshots = targetSnapshots;
    }

    @Override
    @Transactional
    public Task createAndDispatch(TaskCreateRequest request, Long userId) {
        // 无授权调用方（Controller / AI 工具 / AI 自动执行）：门禁默认最严，非 LOW 一律拒绝
        return createAndDispatch(request, userId, null, null);
    }

    @Override
    @Transactional
    public Task createAndDispatch(TaskCreateRequest request, Long userId,
                                  DispatchAuthority authority, String evidenceRef) {
        if (userId == null) {
            throw new BusinessException(403, "Dispatch identity is required");
        }
        String scriptContent = request.getScriptContent();
        String scriptType = "shell";

        if (request.getScriptId() != null) {
            Script script = scriptAccess.requireRead(userId, request.getScriptId());
            if (scriptContent != null) {
                throw new BusinessException(400, "Specify either scriptId or scriptContent");
            }
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

        DispatchEvidenceVerifier workflowVerifier = null;
        DispatchEvidenceVerifier.PreparedEvidence preparedEvidence = null;
        if (authority == DispatchAuthority.HUMAN_APPROVED && isWorkflowEvidence(evidenceRef)) {
            for (DispatchEvidenceVerifier verifier : evidenceVerifiers) {
                if (verifier.supports() == authority) {
                    if (workflowVerifier != null) {
                        throw new BusinessException(449, "派发授权证据不成立，已拒绝派发");
                    }
                    workflowVerifier = verifier;
                }
            }
            if (workflowVerifier == null
                    || (preparedEvidence = workflowVerifier.prepare(evidenceRef, userId)) == null) {
                throw new BusinessException(449, "派发授权证据不成立，已拒绝派发");
            }
        }

        String taskId = "task_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        int effectiveTimeout = request.getTimeoutSeconds() != null ? request.getTimeoutSeconds() : 3600;
        List<TaskTargetSnapshot.Target> targets = new ArrayList<>();
        for (String agentId : resolvedAgentIds.stream().sorted().toList()) {
            var identity = hostIdentityService.lock(agentId);
            if (!identity.active() || identity.generation() <= 0 || !agentRepository.existsById(agentId)
                    || !hostAclService.allowedForGeneration(userId, agentId, identity.generation(), HostAclService.Permission.EXECUTE)) {
                throw new BusinessException(403, "Host execute permission required");
            }
            targets.add(new TaskTargetSnapshot.Target(agentId, identity.generation()));
        }

        // 唯一风险门禁在全部目标 generation 锁定及授权后执行，且在任何任务写入前执行。
        DispatchEvidenceVerifier.DispatchContext context = new DispatchEvidenceVerifier.DispatchContext(
                scriptContent, userId, targets, effectiveTimeout, taskId);
        enforceRiskGate(scriptContent, userId, authority, evidenceRef,
                workflowVerifier, preparedEvidence, context);

        Task task = new Task();
        task.setId(taskId);
        task.setName(request.getName());
        task.setScriptId(request.getScriptId());
        task.setScriptContent(scriptContent);
        task.setScriptType(scriptType);
        task.setTimeoutSeconds(effectiveTimeout);
        task.setStatus(1);
        task.setTotalCount(resolvedAgentIds.size());
        task.setSuccessCount(0);
        task.setFailedCount(0);
        task.setCreatedBy(userId);
        task.setStartedAt(LocalDateTime.now());
        taskRepository.save(task);
        targetSnapshots.create(new TaskTargetSnapshot(task.getId(), targets));

        // Collect dispatch actions to execute AFTER transaction commits
        // This prevents the race condition where fast agents return results
        // before the creating transaction is visible to other threads
        List<Runnable> postCommitDispatches = new ArrayList<>();

        int dispatchedCount = 0;
        for (TaskTargetSnapshot.Target target : targets) {
            String agentId = target.agentId();
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
                boolean authorized;
                try {
                    authorized = hostAclService.allowedForGeneration(userId, capturedAgentId, target.generation(), HostAclService.Permission.EXECUTE);
                } catch (Exception e) {
                    log.warn("Host ACL check failed before dispatch for job {}", capturedJob.getId(), e);
                    authorized = false;
                }
                if (!authorized) {
                    capturedJob.setStatus(3);
                    capturedJob.setOutput("Host execute permission unavailable or revoked");
                    capturedJob.setFinishedAt(LocalDateTime.now());
                    taskCounterService.applyJobResultInNewTransaction(capturedJob, 3);
                    return;
                }
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

    /**
     * 风险门禁（B1-b 咽喉要道的判定核心）：
     * <ul>
     *   <li>无授权（authority == null，Controller / AI 工具 / AI 自动执行）：非 LOW 一律 449；</li>
     *   <li>带授权：工作流与非人工授权均不能放过 BANNED；其余先过证据校验（不成立 449），
     *       通过后同步写审计（授权值 + 证据 + 风险等级 + 命中模式，不存脚本原文）。</li>
     * </ul>
     * 与 TaskController 的事前校验使用同一引擎、同一份（替换后）脚本，结果一致，
     * 已放行的 LOW 脚本二次校验不会行为变化（幂等）。
     */
    private void enforceRiskGate(String scriptContent, Long userId,
                                 DispatchAuthority authority, String evidenceRef,
                                 DispatchEvidenceVerifier workflowVerifier,
                                 DispatchEvidenceVerifier.PreparedEvidence preparedEvidence,
                                 DispatchEvidenceVerifier.DispatchContext context) {
        RiskAssessment risk = riskEngine.assessScript(scriptContent);

        if (authority == null) {
            if (risk.getOverallRisk() != RiskLevel.LOW) {
                log.warn("Blocked dispatch at chokepoint: risk={}, reason={}",
                        risk.getOverallRisk(), risk.getExplanation());
                throw new BusinessException(449, risk.getExplanation());
            }
            return;
        }

        // 工作流 BANNED 在唯一门禁中拒绝，不能消费已准备的证据。
        if (risk.getOverallRisk() == RiskLevel.BANNED
                && (authority != DispatchAuthority.HUMAN_APPROVED || isWorkflowEvidence(evidenceRef))) {
            log.warn("Blocked banned script dispatch despite authority={}", authority);
            throw new BusinessException(449, risk.getExplanation());
        }

        // 授权值只是意图声明，真正放行由证据校验决定（换脚本/换任务/换用户都会失败）
        String evidence = workflowVerifier != null
                ? workflowVerifier.verifyAndConsume(preparedEvidence, evidenceRef, context)
                : verifyDispatchEvidence(authority, evidenceRef, scriptContent, userId);
        if (evidence == null) {
            log.warn("Blocked dispatch: authority={} evidence verification failed", authority);
            throw new BusinessException(449, "派发授权证据不成立，已拒绝派发");
        }

        // 被豁免的派发必须留痕：授权值 + 证据 + 风险等级 + 命中模式（不存脚本原文）
        auditLogService.logSync(userId, "SYSTEM", "SCRIPT_DISPATCH", "task", null,
                "authority=" + authority + ", evidence=" + evidence
                        + ", risk=" + risk.getOverallRisk()
                        + ", matches=" + String.join(",", risk.getBannedMatches()),
                null, "success");
    }

    /**
     * 证据校验：普通任务审批行缺少目标代次及一次性消费绑定，暂不能作为派发证据；
     * 工作流侧的同名授权证据是 {@code workflow_approval:<runId>:<id>}（§6.3.3），
     * 由注册的 {@link DispatchEvidenceVerifier} 校验；其余授权值交给校验器，
     * 没有校验器一律 fail-closed。
     */
    private String verifyDispatchEvidence(DispatchAuthority authority, String evidenceRef,
                                          String scriptContent, Long userId) {
        if (authority == DispatchAuthority.HUMAN_APPROVED && !isWorkflowEvidence(evidenceRef)) {
            // A task row does not bind the actual targets, their generations, timeout,
            // or a single consumption. Never infer a valid authorization from it.
            return null;
        }
        for (DispatchEvidenceVerifier verifier : evidenceVerifiers) {
            if (verifier.supports() == authority) {
                return verifier.verify(evidenceRef, scriptContent, userId);
            }
        }
        return null;
    }

    /** 工作流授权证据前缀：{@code workflow_approval:<runId>:<approvalId>}（§6.3.3） */
    private static boolean isWorkflowEvidence(String evidenceRef) {
        return evidenceRef != null && evidenceRef.startsWith("workflow_approval:");
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
