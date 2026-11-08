package com.easyshell.server.controller;

import com.easyshell.server.ai.model.vo.RiskAssessment;
import com.easyshell.server.ai.risk.CommandRiskEngine;
import com.easyshell.server.ai.risk.RiskLevel;
import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.dto.TaskCreateRequest;
import com.easyshell.server.model.entity.Task;
import com.easyshell.server.model.vo.TaskDetailVO;
import com.easyshell.server.repository.ScriptRepository;
import com.easyshell.server.repository.TaskRepository;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.service.ClusterService;
import com.easyshell.server.service.TagService;
import com.easyshell.server.service.TaskService;
import com.easyshell.server.common.result.R;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B2/B4 回归：task 详情/列表端点的资源归属校验。
 * detail 端点此前已有 requireAccess（此处锁定不回退）；
 * list/page 端点此前不过滤 createdBy，任何登录用户可读全部任务（存量越权读）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TaskController — 任务读写端点的归属校验")
class TaskControllerTest {

    @Mock
    private TaskService taskService;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private CommandRiskEngine riskEngine;
    @Mock
    private ScriptRepository scriptRepository;
    @Mock
    private TaskRepository taskRepository;
    @Mock
    private ClusterService clusterService;
    @Mock
    private TagService tagService;

    private TaskController controller;

    private Authentication auth(Long userId, String role) {
        Authentication auth = mock(Authentication.class);
        // lenient：admin 路径不读 principal；create 路径不读 authorities
        lenient().when(auth.getPrincipal()).thenReturn(userId);
        java.util.Collection<org.springframework.security.core.GrantedAuthority> authorities =
                List.of(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()));
        // thenAnswer 避开 getAuthorities() 通配符捕获导致的 thenReturn 类型约束
        lenient().when(auth.getAuthorities()).thenAnswer(inv -> authorities);
        return auth;
    }

    @BeforeEach
    void setUp() {
        controller = new TaskController(taskService, auditLogService, riskEngine,
                scriptRepository, taskRepository, clusterService, tagService, new ObjectMapper());
    }

    private Task taskOwnedBy(Long userId) {
        Task task = new Task();
        task.setId("task_x");
        task.setName("job");
        task.setCreatedBy(userId);
        return task;
    }

    @Test
    @DisplayName("detail：非属主非管理员 → 403")
    void detail_nonOwner_isDenied() {
        when(taskService.getTaskDetail("task_x"))
                .thenReturn(TaskDetailVO.builder().task(taskOwnedBy(99L)).jobs(List.of()).build());

        assertThatThrownBy(() -> controller.detail("task_x", auth(42L, "operator")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(403));
    }

    @Test
    @DisplayName("detail：属主放行")
    void detail_owner_isAllowed() {
        when(taskService.getTaskDetail("task_x"))
                .thenReturn(TaskDetailVO.builder().task(taskOwnedBy(42L)).jobs(List.of()).build());

        R<TaskDetailVO> result = controller.detail("task_x", auth(42L, "operator"));

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData().getTask().getId()).isEqualTo("task_x");
    }

    @Test
    @DisplayName("detail：管理员可跨用户读取（既有语义）")
    void detail_admin_isAllowed() {
        when(taskService.getTaskDetail("task_x"))
                .thenReturn(TaskDetailVO.builder().task(taskOwnedBy(99L)).jobs(List.of()).build());

        R<TaskDetailVO> result = controller.detail("task_x", auth(7L, "super_admin"));

        assertThat(result.getCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("list：非管理员只看自己的任务")
    void list_nonAdmin_isScopedToOwner() {
        when(taskService.listTasks(42L)).thenReturn(List.of(taskOwnedBy(42L)));

        R<List<Task>> result = controller.list(auth(42L, "operator"));

        assertThat(result.getData()).hasSize(1);
        verify(taskService).listTasks(42L);
        verify(taskService, never()).listTasks();
    }

    @Test
    @DisplayName("list：管理员看全部（既有语义）")
    void list_admin_seesAll() {
        when(taskService.listTasks()).thenReturn(List.of(taskOwnedBy(1L), taskOwnedBy(2L)));

        R<List<Task>> result = controller.list(auth(7L, "admin"));

        assertThat(result.getData()).hasSize(2);
        verify(taskService).listTasks();
    }

    @Test
    @DisplayName("page：非管理员按 status 查询也只返回自己的任务")
    void page_nonAdmin_isScopedToOwner() {
        when(taskService.listTasksByUser(eq(1), eq(42L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(taskOwnedBy(42L))));

        R<Page<Task>> result = controller.page(1, 0, 20, auth(42L, "operator"));

        assertThat(result.getData().getContent()).hasSize(1);
        verify(taskService).listTasksByUser(eq(1), eq(42L), any(Pageable.class));
        verify(taskService, never()).listTasks(anyInt(), any(Pageable.class));
    }

    @Test
    @DisplayName("page：非管理员不带 status 时按属主分页")
    void page_nonAdmin_withoutStatus_isScopedToOwner() {
        when(taskService.listTasksByUser(eq(42L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(taskOwnedBy(42L))));

        R<Page<Task>> result = controller.page(null, 0, 20, auth(42L, "operator"));

        assertThat(result.getData().getContent()).hasSize(1);
        verify(taskService).listTasksByUser(eq(42L), any(Pageable.class));
    }

    @Test
    @DisplayName("page：管理员看全部（既有语义）")
    void page_admin_seesAll() {
        when(taskService.listTasks(eq(1), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(taskOwnedBy(1L), taskOwnedBy(2L))));

        R<Page<Task>> result = controller.page(1, 0, 20, auth(7L, "admin"));

        assertThat(result.getData().getContent()).hasSize(2);
        verify(taskService).listTasks(eq(1), any(Pageable.class));
    }

    @Test
    @DisplayName("create：非 LOW 脚本返回 449 且不派发（Controller 层门禁不回退）")
    void create_nonLowScript_returns449WithoutDispatch() {
        when(riskEngine.assessScript("rm -rf /tmp/x"))
                .thenReturn(RiskAssessment.of(RiskLevel.MEDIUM, List.of()));
        TaskCreateRequest request = new TaskCreateRequest();
        request.setName("risky");
        request.setScriptContent("rm -rf /tmp/x");
        request.setAgentIds(List.of("ag_1"));

        R<?> result = controller.create(request, auth(42L, "operator"), new MockHttpServletRequest());

        assertThat(result.getCode()).isEqualTo(449);
        verify(taskService, never()).createAndDispatch(any(), any());
    }

    @Test
    @DisplayName("create：LOW 脚本正常派发，createdBy 是真实用户")
    void create_lowScript_isDispatchedWithRealUser() {
        when(riskEngine.assessScript("echo hi")).thenReturn(RiskAssessment.of(RiskLevel.LOW, List.of()));
        when(taskService.createAndDispatch(any(), eq(42L))).thenReturn(taskOwnedBy(42L));
        TaskCreateRequest request = new TaskCreateRequest();
        request.setName("safe");
        request.setScriptContent("echo hi");
        request.setAgentIds(List.of("ag_1"));

        R<?> result = controller.create(request, auth(42L, "operator"), new MockHttpServletRequest());

        assertThat(result.getCode()).isEqualTo(200);
        verify(taskService).createAndDispatch(any(), eq(42L));
    }

    @Test
    @DisplayName("page：size 超上限被夹紧（既有防护不回退）")
    void page_oversizedPageSize_isClamped() {
        when(taskService.listTasksByUser(eq(42L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        controller.page(null, 0, 10_000, auth(42L, "operator"));

        verify(taskService).listTasksByUser(eq(42L),
                org.mockito.ArgumentMatchers.argThat((Pageable p) -> p.getPageSize() == 200));
    }

    @Test
    @DisplayName("page：负数页码归零（既有防护不回退）")
    void page_negativePage_isClampedToZero() {
        when(taskService.listTasksByUser(eq(42L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        controller.page(null, -5, 20, auth(42L, "operator"));

        verify(taskService).listTasksByUser(eq(42L),
                org.mockito.ArgumentMatchers.argThat((Pageable p) -> p.getPageNumber() == 0));
    }
}
