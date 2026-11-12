package com.easyshell.server.ai.controller;

import com.easyshell.server.security.AuditUsers;
import com.easyshell.server.ai.model.dto.AiAlertRequest;
import com.easyshell.server.ai.model.dto.AiScheduledTaskRequest;
import com.easyshell.server.ai.model.entity.AiScheduledTask;
import com.easyshell.server.ai.model.vo.AiAlertAnalysis;
import com.easyshell.server.ai.model.vo.AiScheduledTaskVO;
import com.easyshell.server.ai.repository.AiScheduledTaskRepository;
import com.easyshell.server.ai.service.AiAlertResponder;
import com.easyshell.server.ai.service.AiSchedulerService;
import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.common.result.R;
import com.easyshell.server.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/ai/scheduled-tasks")
@RequiredArgsConstructor
public class AiScheduledTaskController {

    private final AiScheduledTaskRepository taskRepository;
    private final AiSchedulerService schedulerService;
    private final AiAlertResponder alertResponder;
    private final AuditLogService auditLogService;

    @GetMapping
    @PreAuthorize("hasAnyRole('VIEWER','OPERATOR','ADMIN','SUPER_ADMIN')")
    public R<List<AiScheduledTaskVO>> list(Authentication auth) {
        Long userId = requireUserId(auth);
        List<AiScheduledTask> tasks = isAdmin(auth) ? taskRepository.findAllByOrderByCreatedAtDesc()
                : taskRepository.findByCreatedByOrderByCreatedAtDesc(userId);
        List<AiScheduledTaskVO> vos = tasks.stream().map(this::toVO).collect(Collectors.toList());
        return R.ok(vos);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('VIEWER','OPERATOR','ADMIN','SUPER_ADMIN')")
    public R<AiScheduledTaskVO> getById(@PathVariable Long id, Authentication auth, HttpServletRequest httpRequest) {
        AiScheduledTask task = requireAccess(id, auth, httpRequest, true, "VIEW_SCHEDULED_TASK");
        return R.ok(toVO(task));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('VIEWER','OPERATOR','ADMIN','SUPER_ADMIN')")
    public R<AiScheduledTaskVO> create(@Valid @RequestBody AiScheduledTaskRequest request,
                                       Authentication auth, HttpServletRequest httpRequest) {
        Long userId = requireUserId(auth);

        AiScheduledTask task = new AiScheduledTask();
        task.setName(request.getName());
        task.setDescription(request.getDescription());
        task.setTaskType(request.getTaskType());
        task.setCronExpression(request.getCronExpression());
        task.setTargetType(request.getTargetType());
        task.setTargetIds(request.getTargetIds());
        task.setScriptTemplate(request.getScriptTemplate());
        task.setAiPrompt(request.getAiPrompt());
        task.setEnabled(request.getEnabled() != null ? request.getEnabled() : true);
        task.setNotifyStrategy(request.getNotifyStrategy());
        task.setNotifyChannels(request.getNotifyChannels());
        task.setNotifyAiPrompt(request.getNotifyAiPrompt());
        task.setCreatedBy(userId);

        task = taskRepository.save(task);

        if (task.getEnabled()) {
            schedulerService.scheduleTask(task);
        }

        auditLogService.log(userId, AuditUsers.name(auth), "CREATE_SCHEDULED_TASK", "ai_scheduled_task",
                String.valueOf(task.getId()), "创建定时任务: " + task.getName(),
                httpRequest.getRemoteAddr(), "success");

        return R.ok(toVO(task));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('VIEWER','OPERATOR','ADMIN','SUPER_ADMIN')")
    public R<AiScheduledTaskVO> update(@PathVariable Long id,
                                       @Valid @RequestBody AiScheduledTaskRequest request,
                                       Authentication auth, HttpServletRequest httpRequest) {
        Long userId = requireUserId(auth);

        AiScheduledTask task = requireAccess(id, auth, httpRequest, false, "UPDATE_SCHEDULED_TASK");

        task.setName(request.getName());
        task.setDescription(request.getDescription());
        task.setTaskType(request.getTaskType());
        task.setCronExpression(request.getCronExpression());
        task.setTargetType(request.getTargetType());
        task.setTargetIds(request.getTargetIds());
        task.setScriptTemplate(request.getScriptTemplate());
        task.setAiPrompt(request.getAiPrompt());
        task.setNotifyStrategy(request.getNotifyStrategy());
        task.setNotifyChannels(request.getNotifyChannels());
        task.setNotifyAiPrompt(request.getNotifyAiPrompt());
        if (request.getEnabled() != null) {
            task.setEnabled(request.getEnabled());
        }

        task = taskRepository.save(task);

        schedulerService.cancelTask(id);
        if (task.getEnabled()) {
            schedulerService.scheduleTask(task);
        }

        auditLogService.log(userId, AuditUsers.name(auth), "UPDATE_SCHEDULED_TASK", "ai_scheduled_task",
                String.valueOf(task.getId()), "更新定时任务: " + task.getName(),
                httpRequest.getRemoteAddr(), "success");

        return R.ok(toVO(task));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('VIEWER','OPERATOR','ADMIN','SUPER_ADMIN')")
    public R<Void> delete(@PathVariable Long id, Authentication auth, HttpServletRequest httpRequest) {
        Long userId = requireUserId(auth);

        AiScheduledTask task = requireAccess(id, auth, httpRequest, true, "DELETE_SCHEDULED_TASK");

        schedulerService.cancelTask(id);
        taskRepository.deleteById(id);

        auditLogService.log(userId, AuditUsers.name(auth), "DELETE_SCHEDULED_TASK", "ai_scheduled_task",
                String.valueOf(id), "删除定时任务: " + task.getName(),
                httpRequest.getRemoteAddr(), "success");

        return R.ok();
    }

    @PostMapping("/{id}/enable")
    @PreAuthorize("hasAnyRole('VIEWER','OPERATOR','ADMIN','SUPER_ADMIN')")
    public R<Void> enable(@PathVariable Long id, Authentication auth, HttpServletRequest httpRequest) {
        AiScheduledTask task = requireAccess(id, auth, httpRequest, false, "ENABLE_SCHEDULED_TASK");
        task.setEnabled(true);
        taskRepository.save(task);
        schedulerService.scheduleTask(task);
        return R.ok();
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("hasAnyRole('VIEWER','OPERATOR','ADMIN','SUPER_ADMIN')")
    public R<Void> disable(@PathVariable Long id, Authentication auth, HttpServletRequest httpRequest) {
        AiScheduledTask task = requireAccess(id, auth, httpRequest, true, "DISABLE_SCHEDULED_TASK");
        task.setEnabled(false);
        taskRepository.save(task);
        schedulerService.cancelTask(id);
        return R.ok();
    }

    @PostMapping("/{id}/run")
    @PreAuthorize("hasAnyRole('VIEWER','OPERATOR','ADMIN','SUPER_ADMIN')")
    public R<Void> runNow(@PathVariable Long id, Authentication auth, HttpServletRequest httpRequest) {
        Long userId = requireUserId(auth);

        AiScheduledTask task = requireAccess(id, auth, httpRequest, false, "MANUAL_RUN_SCHEDULED_TASK");

        schedulerService.executeScheduledTaskAsync(id);

        auditLogService.log(userId, AuditUsers.name(auth), "MANUAL_RUN_SCHEDULED_TASK", "ai_scheduled_task",
                String.valueOf(id), "手动执行定时任务: " + task.getName(),
                httpRequest.getRemoteAddr(), "success");

        return R.ok();
    }

    @GetMapping("/templates")
    public R<List<Map<String, String>>> getTemplates() {
        List<Map<String, String>> templates = AiSchedulerService.BUILT_IN_TEMPLATES.values().stream()
                .map(t -> Map.of(
                        "type", t.type(),
                        "name", t.name(),
                        "description", t.description(),
                        "script", t.script(),
                        "aiPrompt", t.aiPrompt()
                ))
                .collect(Collectors.toList());
        return R.ok(templates);
    }

    @PostMapping("/alert/analyze")
    public R<AiAlertAnalysis> analyzeAlert(@RequestBody AiAlertRequest request) {
        AiAlertAnalysis analysis = alertResponder.analyzeAlert(request);
        return R.ok(analysis);
    }

    private Long requireUserId(Authentication auth) {
        Long userId = AuditUsers.id(auth);
        if (userId == null || !auth.isAuthenticated()) {
            throw new BusinessException(403, "Access denied");
        }
        return userId;
    }

    private boolean isAdmin(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a ->
                "ROLE_ADMIN".equals(a.getAuthority()) || "ROLE_SUPER_ADMIN".equals(a.getAuthority()));
    }

    private AiScheduledTask requireAccess(Long id, Authentication auth, HttpServletRequest request,
                                          boolean adminAllowed, String action) {
        Long userId = requireUserId(auth);
        AiScheduledTask task = taskRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "定时任务不存在"));
        if (!userId.equals(task.getCreatedBy()) && !(adminAllowed && isAdmin(auth))) {
            log.warn("Scheduled task access denied: userId={}, taskId={}, action={}", userId, id, action);
            auditLogService.log(userId, AuditUsers.name(auth), action, "ai_scheduled_task",
                    String.valueOf(id), "Access denied", request.getRemoteAddr(), "denied");
            throw new BusinessException(403, "Access denied");
        }
        return task;
    }

    private AiScheduledTaskVO toVO(AiScheduledTask task) {
        return AiScheduledTaskVO.builder()
                .id(task.getId())
                .name(task.getName())
                .description(task.getDescription())
                .taskType(task.getTaskType())
                .cronExpression(task.getCronExpression())
                .targetType(task.getTargetType())
                .targetIds(task.getTargetIds())
                .scriptTemplate(task.getScriptTemplate())
                .aiPrompt(task.getAiPrompt())
                .enabled(task.getEnabled())
                .lastRunAt(task.getLastRunAt())
                .nextRunAt(task.getNextRunAt())
                .createdBy(task.getCreatedBy())
                .createdAt(task.getCreatedAt())
                .notifyStrategy(task.getNotifyStrategy())
                .notifyChannels(task.getNotifyChannels())
                .notifyAiPrompt(task.getNotifyAiPrompt())
                .updatedAt(task.getUpdatedAt())
                .build();
    }
}
