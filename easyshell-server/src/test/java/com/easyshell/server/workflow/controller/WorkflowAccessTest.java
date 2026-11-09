package com.easyshell.server.workflow.controller;

import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.entity.Workflow;
import com.easyshell.server.model.entity.WorkflowRun;
import com.easyshell.server.repository.WorkflowRepository;
import com.easyshell.server.repository.WorkflowRunRepository;
import com.easyshell.server.service.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WorkflowAccess} 三个数据级校验函数的语义（§6.1：权限注解与数据级校验一一对应）。
 *
 * <p>这里锁死"403 + WARN 审计"与"404 不泄露存在性之外的信息"两件事；
 * 13 个端点的参数化 403 覆盖见 {@code WorkflowControllerTest}。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowAccess — 数据级校验函数")
class WorkflowAccessTest {

    private static final Long OWNER = 42L;
    private static final Long STRANGER = 99L;
    private static final Long ADMIN = 7L;

    @Mock
    private WorkflowRepository workflowRepository;
    @Mock
    private WorkflowRunRepository runRepository;
    @Mock
    private AuditLogService auditLogService;

    private WorkflowAccess access;

    @BeforeEach
    void setUp() {
        access = new WorkflowAccess(workflowRepository, runRepository, auditLogService);
    }

    private Authentication auth(Long userId, String role) {
        Authentication auth = mock(Authentication.class);
        lenient().when(auth.getPrincipal()).thenReturn(userId);
        List<SimpleGrantedAuthority> authorities =
                List.of(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()));
        lenient().when(auth.getAuthorities()).thenAnswer(inv -> authorities);
        lenient().when(auth.getName()).thenReturn("user-" + userId);
        return auth;
    }

    private Workflow workflow() {
        Workflow workflow = new Workflow();
        workflow.setId(1L);
        workflow.setName("cleanup");
        workflow.setCreatedBy(OWNER);
        return workflow;
    }

    private WorkflowRun run() {
        WorkflowRun run = new WorkflowRun();
        run.setId("wfr_abc");
        run.setWorkflowId(1L);
        run.setStartedBy(STRANGER);
        return run;
    }

    @Test
    @DisplayName("requireWorkflowOwner：属主放行")
    void workflowOwner_isAllowed() {
        when(workflowRepository.findById(1L)).thenReturn(Optional.of(workflow()));

        assertThat(access.requireWorkflowOwner(1L, auth(OWNER, "operator")).getCreatedBy()).isEqualTo(OWNER);
    }

    @Test
    @DisplayName("requireWorkflowOwner：非属主 403 + WARN 审计")
    void workflowStranger_isDeniedWithAudit() {
        when(workflowRepository.findById(1L)).thenReturn(Optional.of(workflow()));

        assertThatThrownBy(() -> access.requireWorkflowOwner(1L, auth(STRANGER, "operator")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(403));

        verify(auditLogService).logSync(eq(STRANGER), eq("user-99"), eq("WORKFLOW_ACCESS_DENIED"),
                eq("workflow"), eq("1"), eq("无权访问此工作流"), eq(null), eq("denied"));
    }

    @Test
    @DisplayName("requireWorkflowOwner：admin 可跨用户")
    void workflowAdmin_isAllowed() {
        when(workflowRepository.findById(1L)).thenReturn(Optional.of(workflow()));

        assertThat(access.requireWorkflowOwner(1L, auth(ADMIN, "admin"))).isNotNull();
    }

    @Test
    @DisplayName("requireWorkflowOwner：工作流不存在 404（且不记越权审计）")
    void workflowMissing_is404() {
        when(workflowRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> access.requireWorkflowOwner(1L, auth(STRANGER, "operator")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(404));

        verify(auditLogService, org.mockito.Mockito.never()).logSync(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("requireRunAccess：触发人放行")
    void runStarter_isAllowed() {
        when(runRepository.findById("wfr_abc")).thenReturn(Optional.of(run()));

        assertThat(access.requireRunAccess("wfr_abc", auth(STRANGER, "operator"))).isNotNull();
    }

    @Test
    @DisplayName("requireRunAccess：工作流属主（非触发人）放行")
    void runWorkflowOwner_isAllowed() {
        when(runRepository.findById("wfr_abc")).thenReturn(Optional.of(run()));
        when(workflowRepository.findById(1L)).thenReturn(Optional.of(workflow()));

        assertThat(access.requireRunAccess("wfr_abc", auth(OWNER, "operator"))).isNotNull();
    }

    @Test
    @DisplayName("requireRunAccess：既非触发人也非工作流属主 403 + WARN 审计")
    void runStranger_isDeniedWithAudit() {
        when(runRepository.findById("wfr_abc")).thenReturn(Optional.of(run()));
        when(workflowRepository.findById(1L)).thenReturn(Optional.of(workflow()));

        assertThatThrownBy(() -> access.requireRunAccess("wfr_abc", auth(123L, "operator")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(403));

        verify(auditLogService).logSync(eq(123L), eq("user-123"), eq("WORKFLOW_ACCESS_DENIED"),
                eq("workflow_run"), eq("wfr_abc"), eq("无权访问此运行"), eq(null), eq("denied"));
    }

    @Test
    @DisplayName("requireRunAccess：run 所属工作流缺失时按拒绝处理（fail-closed）")
    void runWithMissingWorkflow_isDenied() {
        when(runRepository.findById("wfr_abc")).thenReturn(Optional.of(run()));
        when(workflowRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> access.requireRunAccess("wfr_abc", auth(OWNER, "operator")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(403));
    }

    @Test
    @DisplayName("requireRunAccess：admin 可跨用户")
    void runAdmin_isAllowed() {
        when(runRepository.findById("wfr_abc")).thenReturn(Optional.of(run()));

        assertThat(access.requireRunAccess("wfr_abc", auth(ADMIN, "super_admin"))).isNotNull();
    }

    @Test
    @DisplayName("requireRunAccess：run 不存在 404")
    void runMissing_is404() {
        when(runRepository.findById("wfr_abc")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> access.requireRunAccess("wfr_abc", auth(OWNER, "operator")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(404));
    }

    @Test
    @DisplayName("requireApprovalAdmin：admin/super_admin 放行，其余 403 + WARN 审计")
    void approvalAdmin_gate() {
        access.requireApprovalAdmin(auth(ADMIN, "admin"));
        access.requireApprovalAdmin(auth(ADMIN, "super_admin"));

        assertThatThrownBy(() -> access.requireApprovalAdmin(auth(STRANGER, "operator")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(403));

        verify(auditLogService).logSync(eq(STRANGER), eq("user-99"), eq("WORKFLOW_ACCESS_DENIED"),
                eq("workflow_approval"), eq(null), eq("审批需要管理员权限"), eq(null), eq("denied"));
    }

    @Test
    @DisplayName("审计写失败时仍然返回 403（越权判定不因审计故障降级）")
    void auditFailure_stillDenies() {
        when(workflowRepository.findById(1L)).thenReturn(Optional.of(workflow()));
        org.mockito.Mockito.doThrow(new RuntimeException("audit db down"))
                .when(auditLogService).logSync(org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());

        assertThatThrownBy(() -> access.requireWorkflowOwner(1L, auth(STRANGER, "operator")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(403));
    }
}
