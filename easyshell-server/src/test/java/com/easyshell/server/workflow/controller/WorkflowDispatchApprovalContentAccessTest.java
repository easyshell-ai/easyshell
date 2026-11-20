package com.easyshell.server.workflow.controller;

import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.entity.WorkflowApproval;
import com.easyshell.server.repository.WorkflowRepository;
import com.easyshell.server.repository.WorkflowRunRepository;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.workflow.service.WorkflowDispatchApprovalAccessService;
import com.easyshell.server.workflow.service.WorkflowRunProvenanceService;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class WorkflowDispatchApprovalContentAccessTest {
    @Test
    void existingTaskVisibilityCannotOverrideDispatchTargetDenial() {
        var access = new WorkflowAccess(mock(WorkflowRepository.class), mock(WorkflowRunRepository.class), mock(AuditLogService.class));
        var provenance = mock(WorkflowRunProvenanceService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(access, "provenanceService", provenance);
        var dedicated = mock(WorkflowDispatchApprovalAccessService.class);
        access.setDispatchApprovalAccess(dedicated);
        var auth = new UsernamePasswordAuthenticationToken(4L, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        var approval = new WorkflowApproval();
        approval.setId(7L);
        approval.setRunId("run");
        approval.setNodeType("script-exec");
        lenient().when(provenance.canView(4L, "run")).thenReturn(true);
        when(dedicated.canView(4L, approval)).thenReturn(false);
        assertThat(access.canViewApproval(auth, approval)).isFalse();
        assertThatThrownBy(() -> access.requireApprovalContentAccess(auth, approval)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(provenance);
    }

    @Test
    void missingOptionalServiceFailsClosedAndDedicatedProofDoesNotReadRun() {
        var runs = mock(WorkflowRunRepository.class);
        var access = new WorkflowAccess(mock(WorkflowRepository.class), runs, mock(AuditLogService.class));
        var auth = new UsernamePasswordAuthenticationToken(4L, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        var approval = new WorkflowApproval();
        approval.setId(7L);
        approval.setRunId("run");
        assertThat(access.canViewApproval(auth, approval)).isFalse();
        assertThatThrownBy(() -> access.requireApprovalContentAccess(auth, approval))
                .isInstanceOf(BusinessException.class);
        var dedicated = mock(WorkflowDispatchApprovalAccessService.class);
        access.setDispatchApprovalAccess(dedicated);
        when(dedicated.canView(4L, approval)).thenReturn(true);
        access.requireApprovalContentAccess(auth, approval);
        verifyNoInteractions(runs);
    }
}
