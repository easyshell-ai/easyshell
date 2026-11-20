package com.easyshell.server.workflow.service;

import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.entity.Workflow;
import com.easyshell.server.model.entity.WorkflowRun;
import com.easyshell.server.model.entity.WorkflowVersion;
import com.easyshell.server.repository.*;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.workflow.engine.WorkflowEngine;
import com.easyshell.server.workflow.properties.WorkflowProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WorkflowRunIdempotencyTest {
    @Test
    void replayRequiresSameWorkflowVersionAndInputIncludingUniqueKeyRace() {
        WorkflowRepository workflows = mock(WorkflowRepository.class);
        WorkflowVersionRepository versions = mock(WorkflowVersionRepository.class);
        WorkflowRunRepository runs = mock(WorkflowRunRepository.class);
        WorkflowVersion version = new WorkflowVersion();
        version.setIsValid(true);
        version.setVersion(1);
        version.setDefinitionJson("{\"nodes\":[],\"edges\":[]}");
        when(workflows.findById(any())).thenReturn(Optional.of(new Workflow()));
        when(versions.findByIdAndWorkflowId(any(), any())).thenReturn(Optional.of(version));
        WorkflowRun first = new WorkflowRun();
        first.setWorkflowId(1L);
        first.setVersionId(2L);
        first.setInputJson("{}");
        when(runs.findByStartedByAndIdempotencyKey(42L, "key")).thenReturn(Optional.of(first));
        WorkflowProperties properties = new WorkflowProperties();
        properties.getEngine().setMinTriggerIntervalSec(0);
        WorkflowRunService service = new WorkflowRunService(workflows, versions, runs,
                mock(WorkflowNodeRunRepository.class), mock(WorkflowApprovalRepository.class),
                mock(WorkflowEngine.class), mock(AuditLogService.class), properties,
                new ObjectMapper(), mock(PlatformTransactionManager.class));
        ReflectionTestUtils.setField(service, "provenanceService", mock(WorkflowRunProvenanceService.class));

        assertThat(service.trigger(1L, 2L, Map.of(), 42L, "key", "manual")).isSameAs(first);
        assertThatThrownBy(() -> service.trigger(3L, 2L, Map.of(), 42L, "key", "manual"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("IDEMPOTENCY_CONFLICT");
        assertThatThrownBy(() -> service.trigger(1L, 3L, Map.of(), 42L, "key", "manual"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("IDEMPOTENCY_CONFLICT");

        when(runs.findByStartedByAndIdempotencyKey(42L, "key"))
                .thenReturn(Optional.empty(), Optional.of(first));
        when(runs.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate"));
        assertThatThrownBy(() -> service.trigger(3L, 2L, Map.of(), 42L, "key", "manual"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("IDEMPOTENCY_CONFLICT");
    }
}
