package com.easyshell.server.controller;

import com.easyshell.server.security.AuditUsers;
import com.easyshell.server.ai.model.vo.RiskAssessment;
import com.easyshell.server.ai.risk.CommandRiskEngine;
import com.easyshell.server.ai.risk.RiskLevel;
import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.common.result.R;
import com.easyshell.server.common.utils.ScriptParameterUtils;
import com.easyshell.server.model.dto.TaskCreateRequest;
import com.easyshell.server.model.entity.Job;
import com.easyshell.server.model.entity.Script;
import com.easyshell.server.model.entity.Task;
import com.easyshell.server.model.vo.TaskDetailVO;
import com.easyshell.server.repository.ScriptRepository;
import com.easyshell.server.repository.TaskRepository;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.service.ClusterService;
import com.easyshell.server.service.TagService;
import com.easyshell.server.service.TaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/task")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;
    private final AuditLogService auditLogService;
    private final CommandRiskEngine riskEngine;
    private final ScriptRepository scriptRepository;
    private final TaskRepository taskRepository;
    private final ClusterService clusterService;
    private final TagService tagService;
    private final ObjectMapper objectMapper;

    private static final int MAX_PAGE_SIZE = 200;

    @PostMapping
    public R<?> create(@Valid @RequestBody TaskCreateRequest request, Authentication auth,
                          HttpServletRequest httpRequest) {
        Long userId = (Long) auth.getPrincipal();

        String scriptContent = request.getScriptContent();
        if (scriptContent == null && request.getScriptId() != null) {
            Script script = scriptRepository.findById(request.getScriptId()).orElse(null);
            if (script != null) {
                scriptContent = script.getContent();
            }
        }

        if (scriptContent != null && request.getParameters() != null && !request.getParameters().isEmpty()) {
            scriptContent = ScriptParameterUtils.substituteParameters(scriptContent, request.getParameters());
            request.setScriptContent(scriptContent);
            request.setScriptId(null);
        }

        if (scriptContent != null && !scriptContent.isBlank()) {
            RiskAssessment risk = riskEngine.assessScript(scriptContent);
            if (risk.getOverallRisk() != RiskLevel.LOW) {
                // Return 449 with risk assessment data so frontend can prompt for approval.
                // Anything that is not explicitly low risk must go through the approval flow,
                // otherwise the MEDIUM gate could be bypassed by posting a task directly.
                return R.of(449, risk.getExplanation(), risk);
            }
        }

        Task task = taskService.createAndDispatch(request, userId);
        auditLogService.log(userId, AuditUsers.name(auth), "CREATE_TASK", "task",
                task.getId(), request.getName(), httpRequest.getRemoteAddr(), "success");
        return R.ok(task);
    }

    @PostMapping("/submit-approval")
    public R<Task> submitForApproval(@Valid @RequestBody TaskCreateRequest request, Authentication auth,
                                     HttpServletRequest httpRequest) {
        Long userId = (Long) auth.getPrincipal();

        String scriptContent = request.getScriptContent();
        String scriptType = "shell";
        if (scriptContent == null && request.getScriptId() != null) {
            Script script = scriptRepository.findById(request.getScriptId())
                    .orElseThrow(() -> new RuntimeException("Script not found"));
            scriptContent = script.getContent();
            scriptType = script.getScriptType();
        }

        if (scriptContent != null && request.getParameters() != null && !request.getParameters().isEmpty()) {
            scriptContent = ScriptParameterUtils.substituteParameters(scriptContent, request.getParameters());
        }

        if (scriptContent == null || scriptContent.isBlank()) {
            return R.fail(400, "Script content is required");
        }

        // Re-assess risk to confirm it truly needs approval
        RiskAssessment risk = riskEngine.assessScript(scriptContent);
        if (risk.getOverallRisk() == RiskLevel.LOW) {
            return R.fail(400, "Script does not require approval");
        }

        // Resolve agent IDs from clusterIds/tagIds (same logic as TaskServiceImpl)
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
            return R.fail(400, "At least one agent must be specified");
        }

        // Create pending approval task (same pattern as AiExecutionService.createPendingApprovalTask)
        try {
            Task task = new Task();
            task.setId("task_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
            task.setName(request.getName());
            task.setScriptId(request.getScriptId());
            task.setScriptContent(scriptContent);
            task.setScriptType(scriptType);
            task.setTimeoutSeconds(request.getTimeoutSeconds() != null ? request.getTimeoutSeconds() : 600);
            task.setStatus(6); // pending approval
            task.setTotalCount(resolvedAgentIds.size());
            task.setSuccessCount(0);
            task.setFailedCount(0);
            task.setCreatedBy(userId);
            task.setSource("manual");
            task.setRiskLevel(risk.getOverallRisk().name());
            task.setApprovalStatus("pending");
            task.setTargetAgentIds(objectMapper.writeValueAsString(new ArrayList<>(resolvedAgentIds)));
            taskRepository.save(task);

            auditLogService.log(userId, AuditUsers.name(auth), "SUBMIT_APPROVAL", "task",
                    task.getId(), request.getName(), httpRequest.getRemoteAddr(), "pending");

            log.info("Manual task {} submitted for approval by user {}, risk level: {}",
                    task.getId(), userId, risk.getOverallRisk());

            return R.ok(task);
        } catch (Exception e) {
            log.error("Failed to create pending approval task", e);
            return R.fail(500, "Failed to submit for approval: " + e.getMessage());
        }
    }

    @GetMapping("/list")
    public R<List<Task>> list(Authentication auth) {
        // 归属过滤（B2）：管理员看全部，其余角色只能看自己创建的任务，
        // 与 agentJobs / detail 端点的所有权语义保持一致。
        if (isAdmin(auth)) {
            return R.ok(taskService.listTasks());
        }
        return R.ok(taskService.listTasks((Long) auth.getPrincipal()));
    }

    @GetMapping("/page")
    public R<Page<Task>> page(
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication auth) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(safePage, safeSize);
        if (!isAdmin(auth)) {
            Long userId = (Long) auth.getPrincipal();
            Page<Task> tasks = status != null
                    ? taskService.listTasksByUser(status, userId, pageable)
                    : taskService.listTasksByUser(userId, pageable);
            return R.ok(tasks);
        }
        return R.ok(taskService.listTasks(status, pageable));
    }

    @GetMapping("/{taskId}")
    public R<TaskDetailVO> detail(@PathVariable String taskId, Authentication auth) {
        TaskDetailVO detail = taskService.getTaskDetail(taskId);
        requireAccess(auth, detail.getTask().getCreatedBy());
        return R.ok(detail);
    }

    @GetMapping("/agent/{agentId}/jobs")
    public R<List<Job>> agentJobs(@PathVariable String agentId, Authentication auth) {
        if (!isAdmin(auth)) {
            // Non-administrators only see jobs belonging to their own tasks
            Long userId = (Long) auth.getPrincipal();
            List<Job> jobs = taskService.getAgentJobs(agentId).stream()
                    .filter(job -> {
                        Task task = taskRepository.findById(job.getTaskId()).orElse(null);
                        return task != null && userId.equals(task.getCreatedBy());
                    })
                    .toList();
            return R.ok(jobs);
        }
        return R.ok(taskService.getAgentJobs(agentId));
    }

    private void requireAccess(Authentication auth, Long createdBy) {
        if (!isAdmin(auth) && !((Long) auth.getPrincipal()).equals(createdBy)) {
            throw new BusinessException(403, "Not allowed to access this task");
        }
    }

    private boolean isAdmin(Authentication auth) {
        return auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_SUPER_ADMIN")
                        || a.getAuthority().equals("ROLE_ADMIN"));
    }
}
