package com.easyshell.server.ai.service;

import com.easyshell.server.ai.risk.CommandRiskEngine;
import com.easyshell.server.model.entity.Task;
import com.easyshell.server.repository.TaskRepository;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.service.DispatchAuthority;
import com.easyshell.server.service.TaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiExecutionServiceHostAclTest {
    @Test
    void approvalDispatchesAsApplicantAndAuditsApprover() {
        TaskService tasks = mock(TaskService.class);
        TaskRepository repository = mock(TaskRepository.class);
        AuditLogService audit = mock(AuditLogService.class);
        AiExecutionService service = new AiExecutionService(mock(CommandRiskEngine.class), tasks, repository, audit, new ObjectMapper());
        Task pending = new Task();
        pending.setId("pending");
        pending.setCreatedBy(10L);
        pending.setApprovalStatus("pending");
        pending.setScriptContent("echo hi");
        pending.setTargetAgentIds("[\"host\"]");
        when(repository.findById("pending")).thenReturn(Optional.of(pending));
        Task executed = new Task();
        executed.setId("executed");
        when(tasks.createAndDispatch(any(), eq(10L), eq(DispatchAuthority.HUMAN_APPROVED), eq("pending")))
                .thenReturn(executed);

        service.approveExecution("pending", 20L);

        verify(tasks).createAndDispatch(any(), eq(10L), eq(DispatchAuthority.HUMAN_APPROVED), eq("pending"));
        verify(audit).logSync(eq(20L), eq("USER"), eq("AI_EXECUTION_APPROVE"), eq("task"), eq("pending"),
                contains("applicant=10"), isNull(), eq("success"));
    }
}
