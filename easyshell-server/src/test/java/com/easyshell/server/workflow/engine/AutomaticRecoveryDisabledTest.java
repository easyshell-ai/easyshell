package com.easyshell.server.workflow.engine;

import com.easyshell.server.repository.WorkflowApprovalRepository;
import com.easyshell.server.repository.WorkflowNodeRunRepository;
import com.easyshell.server.repository.WorkflowRunRepository;
import com.easyshell.server.repository.WorkflowVersionRepository;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.workflow.approval.WorkflowApprovalStore;
import com.easyshell.server.workflow.approval.WorkflowResumeService;
import com.easyshell.server.workflow.properties.WorkflowProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AutomaticRecoveryDisabledTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void scannersDoNotRewriteUnknownWorkInDefaultOrDurableMode(boolean durable) {
        var properties = new WorkflowProperties();
        assertThat(properties.getEngine().isAutomaticRecoveryEnabled()).isFalse();
        properties.getEngine().setDurableResumeEnabled(durable);
        properties.getEngine().setAutomaticRecoveryEnabled(durable);
        var runs = mock(WorkflowRunRepository.class);
        var nodes = mock(WorkflowNodeRunRepository.class);
        var approvals = mock(WorkflowApprovalRepository.class);
        var versions = mock(WorkflowVersionRepository.class);
        var store = mock(WorkflowApprovalStore.class);
        var engine = mock(WorkflowEngine.class);
        var audit = mock(AuditLogService.class);
        var tx = mock(PlatformTransactionManager.class);

        when(runs.countByStatusIn(anyList())).thenReturn(0L);
        when(runs.findByStatusIn(List.of("queued"))).thenReturn(List.of());
        when(runs.findByStatusInAndStartedAtBefore(anyList(), any())).thenReturn(List.of());
        new WorkflowPendingWakeupScanner(runs, engine, audit, properties, tx).scan();
        verify(runs).findByStatusIn(List.of("queued"));
        verify(runs, never()).findByStatusInAndStartedAtBefore(eq(List.of("running")), any());

        if (!durable) {
            new WorkflowResumeService(store, runs, nodes, engine, audit, properties, tx).scanWakeups();
            verify(nodes, never()).findByStatus("waiting");
        }
        new WorkflowWatchdog(runs, nodes, approvals, versions, store, engine, audit,
                properties, new ObjectMapper(), tx).scan();
        verify(nodes, never()).findByStatus("running");
        verifyNoInteractions(store, engine);
    }
}
