package com.easyshell.server.controller;

import com.easyshell.server.security.AuditUsers;
import com.easyshell.server.ai.model.vo.RiskAssessment;
import com.easyshell.server.ai.risk.CommandRiskEngine;
import com.easyshell.server.ai.risk.RiskLevel;
import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.common.result.R;
import com.easyshell.server.common.utils.ScriptParameterUtils;
import com.easyshell.server.model.dto.TaskCreateRequest;
import com.easyshell.server.model.dto.TaskTargetSnapshot;
import com.easyshell.server.model.entity.Job;
import com.easyshell.server.model.entity.Script;
import com.easyshell.server.model.entity.Task;
import com.easyshell.server.model.vo.TaskDetailVO;
import com.easyshell.server.model.vo.TaskRiskAssessmentVO;
import com.easyshell.server.security.ScriptAccess;
import com.easyshell.server.repository.TaskRepository;
import com.easyshell.server.repository.TaskAccessQueryRepository;
import com.easyshell.server.repository.TaskTargetSnapshotRepository;
import com.easyshell.server.repository.AgentRepository;
import com.easyshell.server.repository.WorkflowNodeRunRepository;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.service.HostAclService;
import com.easyshell.server.service.HostIdentityService;
import com.easyshell.server.workflow.controller.WorkflowAccess;
import com.easyshell.server.service.ClusterService;
import com.easyshell.server.service.TagService;
import com.easyshell.server.service.TaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/task")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;
    private final AuditLogService auditLogService;
    private final CommandRiskEngine riskEngine;
    private final ScriptAccess scriptAccess;
    private final TaskRepository taskRepository;
    private final ClusterService clusterService;
    private final TagService tagService;
    private final ObjectMapper objectMapper;
    private final WorkflowNodeRunRepository nodeRunRepository;
    private final WorkflowAccess workflowAccess;
    private final HostAclService hostAclService;
    private final HostIdentityService hostIdentityService;
    private final AgentRepository agentRepository;
    private final TaskTargetSnapshotRepository targetSnapshots;
    private final TaskAccessQueryRepository taskAccessQueries;

    private static final String TASK_IDENTITY_UNAVAILABLE =
            "TASK_RESOURCE_IDENTITY_UNAVAILABLE: task host identity cannot be verified";

    @PostMapping
    public R<?> create(@Valid @RequestBody TaskCreateRequest request, Authentication auth,
                          HttpServletRequest httpRequest) {
        try {
            return createTask(request, auth, httpRequest);
        } catch (BusinessException e) {
            if (e.getCode() == 449) {
                // A later dispatch assessment can fail after the initial one, and its
                // exception message may contain a user-configured literal rule/command.
                throw new BusinessException(449, "需人工确认");
            }
            throw e;
        }
    }

    private R<?> createTask(TaskCreateRequest request, Authentication auth, HttpServletRequest httpRequest) {
        Long userId = scriptActor(auth);

        String scriptContent = request.getScriptContent();
        if (request.getScriptId() != null) {
            Script script = scriptAccess.requireRead(userId, request.getScriptId());
            rejectAmbiguousScript(request);
            scriptContent = script.getContent();
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
                TaskRiskAssessmentVO response = TaskRiskAssessmentVO.from(risk);
                return R.of(449, response.getExplanation(), response);
            }
        }

        Task task = taskService.createAndDispatch(request, userId);
        auditLogService.log(userId, AuditUsers.name(auth), "CREATE_TASK", "task",
                task.getId(), request.getName(), httpRequest.getRemoteAddr(), "success");
        return R.ok(task);
    }

    @PostMapping("/submit-approval")
    @Transactional
    public R<Task> submitForApproval(@Valid @RequestBody TaskCreateRequest request, Authentication auth,
                                     HttpServletRequest httpRequest) {
        Long userId = scriptActor(auth);

        String scriptContent = request.getScriptContent();
        String scriptType = "shell";
        if (request.getScriptId() != null) {
            Script script = scriptAccess.requireRead(userId, request.getScriptId());
            rejectAmbiguousScript(request);
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

        List<TaskTargetSnapshot.Target> targets = new ArrayList<>();
        for (String agentId : resolvedAgentIds.stream().sorted().toList()) {
            var identity = hostIdentityService.lock(agentId);
            if (!identity.active() || identity.generation() <= 0 || !agentRepository.existsById(agentId)
                    || !hostAclService.allowedForGeneration(userId, agentId, identity.generation(), HostAclService.Permission.EXECUTE)) {
                throw new BusinessException(403, "Host execute permission required");
            }
            targets.add(new TaskTargetSnapshot.Target(agentId, identity.generation()));
        }

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
            targetSnapshots.create(new TaskTargetSnapshot(task.getId(), targets));

            auditLogService.log(userId, AuditUsers.name(auth), "SUBMIT_APPROVAL", "task",
                    task.getId(), request.getName(), httpRequest.getRemoteAddr(), "pending");

            log.info("Manual task {} submitted for approval by user {}, risk level: {}",
                    task.getId(), userId, risk.getOverallRisk());

            return R.ok(task);
        } catch (Exception e) {
            log.error("Failed to create pending approval task", e);
            throw new IllegalStateException("Failed to submit for approval", e);
        }
    }

    @GetMapping("/list")
    public R<List<Task>> list(Authentication auth) {
        return R.ok(taskAccessQueries.list((Long) auth.getPrincipal()));
    }

    @GetMapping("/page")
    public R<Page<Task>> page(
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication auth) {
        return R.ok(taskAccessQueries.page((Long) auth.getPrincipal(), status, page, size));
    }

    @GetMapping("/{taskId}")
    public R<TaskDetailVO> detail(@PathVariable String taskId, Authentication auth) {
        Long actor = (Long) auth.getPrincipal();
        String currentRole = taskAccessQueries.currentRole(actor);
        Authentication currentAuth = new UsernamePasswordAuthenticationToken(actor, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + currentRole.toUpperCase(Locale.ROOT))));
        TaskDetailVO detail = taskService.getTaskDetail(taskId);
        requireAccess(currentAuth, detail.getTask().getCreatedBy());
        var snapshot = targetSnapshots.findComplete(taskId).orElseThrow(this::taskIdentityUnavailable);
        Set<String> targetIds = new HashSet<>();
        for (var target : snapshot.targets()) targetIds.add(target.agentId());
        if (!Objects.equals(detail.getTask().getTotalCount(), targetIds.size())
                || detail.getJobs() == null
                || detail.getJobs().stream().anyMatch(job -> !targetIds.contains(job.getAgentId()))) {
            throw taskIdentityUnavailable();
        }
        Long userId = (Long) auth.getPrincipal();
        for (var target : snapshot.targets()) {
            if (!hostAclService.allowedForGeneration(userId, target.agentId(), target.generation(), HostAclService.Permission.VIEW)) {
                throw new BusinessException(403, "Host view permission required");
            }
        }
        var nodes = nodeRunRepository.findByTaskId(taskId);
        var provenanceRuns = taskAccessQueries.provenanceRunIds(taskId);
        Set<String> nodeRunIds = new HashSet<>();
        for (var node : nodes) nodeRunIds.add(node.getRunId());
        if (("workflow".equals(detail.getTask().getSource()) && nodes.isEmpty())
                || !nodeRunIds.containsAll(provenanceRuns)
                || !new HashSet<>(provenanceRuns).containsAll(nodeRunIds)) {
            throw taskIdentityUnavailable();
        }
        if (!nodes.isEmpty()) {
            for (var node : nodes) workflowAccess.requireRunAccess(node.getRunId(), currentAuth);
            var node = nodes.get(0);
            detail.setWorkflowSource(new TaskDetailVO.WorkflowSource(node.getRunId(), node.getNodeKey(), node.getAttempt()));
        }
        return R.ok(detail);
    }

    @GetMapping("/agent/{agentId}/jobs")
    public R<List<Job>> agentJobs(@PathVariable String agentId, Authentication auth) {
        return R.ok(taskAccessQueries.agentJobs((Long) auth.getPrincipal(), agentId));
    }

    private BusinessException taskIdentityUnavailable() {
        return new BusinessException(503, TASK_IDENTITY_UNAVAILABLE);
    }

    private Long scriptActor(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof Long userId)) {
            throw new BusinessException(401, "Authentication required");
        }
        return userId;
    }

    private void rejectAmbiguousScript(TaskCreateRequest request) {
        if (request.getScriptContent() != null) {
            throw new BusinessException(400, "Specify either scriptId or scriptContent");
        }
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
