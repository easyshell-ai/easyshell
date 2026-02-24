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

        for (String agentId : resolvedAgentIds) {
            Agent agent = agentRepository.findById(agentId).orElse(null);
            if (agent == null || agent.getStatus() == 0) {
                log.warn("Skipping offline/unknown agent: {}", agentId);
                continue;
            }

            Job job = new Job();
            job.setId("job_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
            job.setTaskId(task.getId());
            job.setAgentId(agentId);
            job.setStatus(0);
            jobRepository.save(job);

            boolean dispatched = agentWebSocketHandler.dispatchJob(
                    agentId, job, scriptContent, task.getTimeoutSeconds());
            if (!dispatched) {
                log.warn("Agent {} not connected via WebSocket, job {} left as pending", agentId, job.getId());
            }
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
        Job job = jobRepository.findById(request.getJobId())
                .orElseThrow(() -> new BusinessException(404, "Job not found"));

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
}
