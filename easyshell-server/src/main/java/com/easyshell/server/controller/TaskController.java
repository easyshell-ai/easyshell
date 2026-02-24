package com.easyshell.server.controller;

import com.easyshell.server.common.result.R;
import com.easyshell.server.model.dto.TaskCreateRequest;
import com.easyshell.server.model.entity.Job;
import com.easyshell.server.model.entity.Task;
import com.easyshell.server.model.vo.TaskDetailVO;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.service.TaskService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/task")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;
    private final AuditLogService auditLogService;

    @PostMapping
    public R<Task> create(@Valid @RequestBody TaskCreateRequest request, Authentication auth,
                          HttpServletRequest httpRequest) {
        Long userId = (Long) auth.getPrincipal();
        Task task = taskService.createAndDispatch(request, userId);
        auditLogService.log(userId, auth.getName(), "CREATE_TASK", "task",
                task.getId(), request.getName(), httpRequest.getRemoteAddr(), "success");
        return R.ok(task);
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
