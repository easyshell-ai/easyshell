package com.easyshell.server.ai.service;

import com.easyshell.server.ai.model.entity.AiInspectReport;
import com.easyshell.server.ai.model.entity.AiScheduledTask;
import com.easyshell.server.ai.repository.AiInspectReportRepository;
import com.easyshell.server.model.dto.TaskCreateRequest;
import com.easyshell.server.model.entity.Task;
import com.easyshell.server.service.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class InspectExecutionServiceTest {
    @Test
    void dispatchesWithoutClaimingUnboundRiskExemption() {
        TaskService tasks = mock(TaskService.class);
        AiInspectReportRepository reports = mock(AiInspectReportRepository.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AiScheduledTask scheduled = new AiScheduledTask();
        scheduled.setId(7L);
        scheduled.setCreatedBy(42L);
        TaskCreateRequest request = new TaskCreateRequest();
        Task task = new Task();
        task.setId("task-1");
        when(tasks.createAndDispatch(request, 42L)).thenReturn(task);
        when(reports.saveAndFlush(any(AiInspectReport.class))).thenAnswer(invocation -> {
            AiInspectReport report = invocation.getArgument(0);
            report.setId(9L);
            return report;
        });

        new InspectExecutionService(tasks, reports, jdbc).create(scheduled, request);

        verify(tasks).createAndDispatch(request, 42L);
        verify(jdbc).update(anyString(), eq(9L), eq("task-1"));
    }
}
