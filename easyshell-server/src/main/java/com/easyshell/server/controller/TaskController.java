package com.easyshell.server.controller;

import com.easyshell.server.ai.model.vo.RiskAssessment;
import com.easyshell.server.ai.risk.CommandRiskEngine;
import com.easyshell.server.ai.risk.RiskLevel;
import com.easyshell.server.common.result.R;
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

    @PostMapping
    public R<?> create(@Valid @RequestBody TaskCreateRequest request, Authentication auth,
                          HttpServletRequest httpRequest) {
        Long userId = (Long) auth.getPrincipal();

        // Resolve script content for risk assessment
        String scriptContent = request.getScriptContent();
        if (scriptContent == null && request.getScriptId() != null) {
            Script script = scriptRepository.findById(request.getScriptId()).orElse(null);
            if (script != null) {
                scriptContent = script.getContent();
            }
        }

        // Risk assessment for manual execution
        if (scriptContent != null && !scriptContent.isBlank()) {
            RiskAssessment risk = riskEngine.assessScript(scriptContent);
            if (risk.getOverallRisk() == RiskLevel.BANNED || risk.getOverallRisk() == RiskLevel.HIGH) {
                // Return 449 with risk assessment data so frontend can prompt for approval
                return R.of(449, risk.getExplanation(), risk);
            }
        }

        Task task = taskService.createAndDispatch(request, userId);
        auditLogService.log(userId, auth.getName(), "CREATE_TASK", "task",
                task.getId(), request.getName(), httpRequest.getRemoteAddr(), "success");
        return R.ok(task);
    }

    @PostMapping("/submit-approval")
    public R<Task> submitForApproval(@Valid @RequestBody TaskCreateRequest request, Authentication auth,
                                     HttpServletRequest httpRequest) {
        Long userId = (Long) auth.getPrincipal();

        // Resolve script content
        String scriptContent = request.getScriptContent();
        String scriptType = "shell";
        if (scriptContent == null && request.getScriptId() != null) {
            Script script = scriptRepository.findById(request.getScriptId())
                    .orElseThrow(() -> new RuntimeException("Script not found"));
            scriptContent = script.getContent();
            scriptType = script.getScriptType();
        }

        if (scriptContent == null || scriptContent.isBlank()) {
            return R.fail(400, "Script content is required");
        }

        // Re-assess risk to confirm it truly needs approval
        RiskAssessment risk = riskEngine.assessScript(scriptContent);
        if (risk.getOverallRisk() != RiskLevel.BANNED && risk.getOverallRisk() != RiskLevel.HIGH) {
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

            auditLogService.log(userId, auth.getName(), "SUBMIT_APPROVAL", "task",
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
    public R<List<Task>> list() {
        return R.ok(taskService.listTasks());
    }

    @GetMapping("/page")
    public R<Page<Task>> page(
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(taskService.listTasks(status, PageRequest.of(page, size)));
    }

    @GetMapping("/{taskId}")
    public R<TaskDetailVO> detail(@PathVariable String taskId) {
        return R.ok(taskService.getTaskDetail(taskId));
    }

    @GetMapping("/agent/{agentId}/jobs")
    public R<List<Job>> agentJobs(@PathVariable String agentId) {
        return R.ok(taskService.getAgentJobs(agentId));
    }
}
