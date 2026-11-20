package com.easyshell.server.workflow.service;

import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.entity.Workflow;
import com.easyshell.server.model.entity.WorkflowVersion;
import com.easyshell.server.repository.WorkflowApprovalRepository;
import com.easyshell.server.repository.WorkflowNodeRunRepository;
import com.easyshell.server.repository.WorkflowRepository;
import com.easyshell.server.repository.WorkflowRunRepository;
import com.easyshell.server.repository.WorkflowVersionRepository;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.workflow.engine.WorkflowEngine;
import com.easyshell.server.workflow.properties.WorkflowProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("触发入参 — label/options 向后兼容与枚举校验")
class WorkflowRunServiceInputValidationTest {

    private static final String DEFINITION = """
            {"schemaVersion":1,
             "variables":{
               "choice":{"type":"string","label":"选择","options":["a","b"]},
               "count":{"type":"number","options":["1","2"]}},
             "nodes":[],"edges":[]}
            """;

    private WorkflowRunService service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        WorkflowRepository workflowRepository = mock(WorkflowRepository.class);
        WorkflowVersionRepository versionRepository = mock(WorkflowVersionRepository.class);

        Workflow workflow = new Workflow();
        workflow.setId(1L);
        WorkflowVersion version = new WorkflowVersion();
        version.setId(1L);
        version.setVersion(1);
        version.setIsValid(true);
        version.setDefinitionJson(DEFINITION);

        when(workflowRepository.findById(1L)).thenReturn(Optional.of(workflow));
        when(versionRepository.findByIdAndWorkflowId(1L, 1L)).thenReturn(Optional.of(version));

        service = new WorkflowRunService(workflowRepository, versionRepository,
                mock(WorkflowRunRepository.class), mock(WorkflowNodeRunRepository.class),
                mock(WorkflowApprovalRepository.class), mock(WorkflowEngine.class),
                mock(AuditLogService.class), new WorkflowProperties(), objectMapper,
                mock(PlatformTransactionManager.class));
        ReflectionTestUtils.setField(service, "provenanceService", mock(WorkflowRunProvenanceService.class));
    }

    @Test
    @DisplayName("string options：值不在候选内 → 400 WORKFLOW_PARAM_INVALID")
    void unknownOptionIsRejected() {
        assertThatThrownBy(() -> service.trigger(1L, 1L, Map.of("choice", "c"), 42L, null, "manual"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("WORKFLOW_PARAM_INVALID").contains("choice");
                });
    }

    @Test
    @DisplayName("非 string 变量声明 options → 400 WORKFLOW_TYPE_MISMATCH")
    void optionsOnNonStringIsRejected() {
        assertThatThrownBy(() -> service.trigger(1L, 1L, Map.of("choice", "a", "count", 1),
                42L, null, "manual"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("WORKFLOW_TYPE_MISMATCH").contains("count");
                });
    }
}
