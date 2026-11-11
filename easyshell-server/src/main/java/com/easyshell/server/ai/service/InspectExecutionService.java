package com.easyshell.server.ai.service;

import com.easyshell.server.ai.model.entity.AiInspectReport;
import com.easyshell.server.ai.model.entity.AiScheduledTask;
import com.easyshell.server.ai.repository.AiInspectReportRepository;
import com.easyshell.server.model.dto.TaskCreateRequest;
import com.easyshell.server.model.entity.Task;
import com.easyshell.server.service.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InspectExecutionService {
    private final TaskService tasks;
    private final AiInspectReportRepository reports;
    private final JdbcTemplate jdbc;

    public record Execution(Task task, AiInspectReport report) { }

    @Transactional
    public Execution create(AiScheduledTask scheduled, TaskCreateRequest request) {
        // Until a scheduled intent binds the final script and targets, do not claim an
        // exemption. The same dispatch gate still admits LOW risk and rejects the rest.
        Task task = tasks.createAndDispatch(request, scheduled.getCreatedBy());
        AiInspectReport report = new AiInspectReport();
        report.setScheduledTaskId(scheduled.getId());
        report.setTaskType(scheduled.getTaskType());
        report.setTaskName(scheduled.getName());
        report.setTargetSummary(scheduled.getTargetType() + ": " + scheduled.getTargetIds());
        report.setCreatedBy(scheduled.getCreatedBy());
        report.setStatus("running");
        reports.saveAndFlush(report);
        jdbc.update("INSERT INTO ai_inspect_report_task_binding (report_id, task_id) VALUES (?, ?)", report.getId(), task.getId());
        return new Execution(task, report);
    }
}
