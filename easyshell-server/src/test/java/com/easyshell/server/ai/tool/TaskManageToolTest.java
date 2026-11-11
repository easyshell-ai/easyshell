package com.easyshell.server.ai.tool;

import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.dto.TaskCreateRequest;
import com.easyshell.server.model.dto.TaskTargetSnapshot;
import com.easyshell.server.model.entity.Job;
import com.easyshell.server.model.entity.Task;
import com.easyshell.server.model.vo.TaskDetailVO;
import com.easyshell.server.repository.AgentRepository;
import com.easyshell.server.repository.SystemConfigRepository;
import com.easyshell.server.repository.TaskTargetSnapshotRepository;
import com.easyshell.server.service.HostAclService;
import com.easyshell.server.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * B1-a 回归：AI 工具曾把 userId 硬编码成 1L，审计永远记成"用户 1"。
 * 修复后必须从会话上下文取真实用户，上下文缺失时拒绝执行（fail-closed）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TaskManageTool — 用户上下文与归属校验")
class TaskManageToolTest {

    @Mock
    private TaskService taskService;
    @Mock
    private AgentRepository agentRepository;
    @Mock
    private SystemConfigRepository systemConfigRepository;
    @Mock private TaskTargetSnapshotRepository snapshots;
    @Mock private HostAclService acl;

    private TaskManageTool tool;

    @BeforeEach
    void setUp() {
        tool = new TaskManageTool(taskService, agentRepository, systemConfigRepository, snapshots, acl);
    }

    private Task task(String id, Long createdBy) {
        Task task = new Task();
        task.setId(id);
        task.setName("job");
        task.setCreatedBy(createdBy);
        task.setTotalCount(1);
        task.setStatus(2);
        return task;
    }

    private void allow(String taskId) {
        when(snapshots.findComplete(taskId)).thenReturn(Optional.of(new TaskTargetSnapshot(taskId,
                List.of(new TaskTargetSnapshot.Target("ag_1", 7L)))));
        when(acl.allowedForGeneration(42L, "ag_1", 7L, HostAclService.Permission.VIEW)).thenReturn(true);
    }

    @Test
    @DisplayName("createTask 使用上下文中的真实 userId，而不是硬编码 1L")
    void createTask_usesRealUserIdFromContext() {
        when(agentRepository.existsById("ag_1")).thenReturn(true);
        Task created = task("task_new", 42L);
        when(taskService.createAndDispatch(any(TaskCreateRequest.class), eq(42L))).thenReturn(created);

        tool = tool.forUser(42L, "10.0.0.1");
        String result = tool.createTask("df -h", "ag_1", "disk check", 60);

        assertThat(result).contains("task_new");
        verify(taskService).createAndDispatch(any(TaskCreateRequest.class), eq(42L));
        verify(taskService, never()).createAndDispatch(any(TaskCreateRequest.class), eq(1L));
    }

    @Test
    @DisplayName("没有用户上下文时拒绝创建任务（fail-closed，不落到臆造用户）")
    void createTask_withoutContext_isRefused() {
        String result = tool.createTask("df -h", "ag_1", "disk check", 60);

        assertThat(result).contains("拒绝");
        verifyNoInteractions(taskService);
    }

    @Test
    @DisplayName("咽喉要道门禁拦下的高危脚本：错误原因透传给模型，不静默成功")
    void createTask_blockedByRiskGate_surfacesReason() {
        when(agentRepository.existsById("ag_1")).thenReturn(true);
        when(taskService.createAndDispatch(any(TaskCreateRequest.class), eq(42L)))
                .thenThrow(new BusinessException(449, "包含高危命令，需管理员审批后执行"));

        tool = tool.forUser(42L, "10.0.0.1");
        String result = tool.createTask("rm -rf /tmp/x", "ag_1", "cleanup", 60);

        assertThat(result).contains("创建任务失败");
        assertThat(result).contains("高危命令");
    }

    @Test
    @DisplayName("getTaskDetail 只能看自己的任务：他人任务返回无权查看")
    void getTaskDetail_othersTask_isDenied() {
        Task foreign = task("task_foreign", 99L);
        when(taskService.getTaskDetail("task_foreign")).thenReturn(
                TaskDetailVO.builder().task(foreign).jobs(List.of()).build());

        tool = tool.forUser(42L, "10.0.0.1");
        String result = tool.getTaskDetail("task_foreign");

        assertThat(result).contains("无权查看");
    }

    @Test
    @DisplayName("getTaskDetail 看自己的任务正常返回详情")
    void getTaskDetail_ownTask_isReturned() {
        allow("task_mine");
        Task mine = task("task_mine", 42L);
        Job job = new Job();
        job.setAgentId("ag_1");
        job.setStatus(2);
        job.setExitCode(0);
        job.setOutput("ok");
        when(taskService.getTaskDetail("task_mine")).thenReturn(
                TaskDetailVO.builder().task(mine).jobs(List.of(job)).build());

        tool = tool.forUser(42L, "10.0.0.1");
        String result = tool.getTaskDetail("task_mine");

        assertThat(result).contains("task_mine");
        assertThat(result).contains("ok");
    }

    @Test
    @DisplayName("listRecentTasks 只列当前用户的任务")
    void listRecentTasks_scopedToCurrentUser() {
        allow("task_mine");
        when(taskService.listTasks(42L)).thenReturn(List.of(task("task_mine", 42L)));

        tool = tool.forUser(42L, "10.0.0.1");
        String result = tool.listRecentTasks();

        assertThat(result).contains("task_mine");
        verify(taskService).listTasks(42L);
        verify(taskService, never()).listTasks();
    }

    @Test
    void incompleteOrUnauthorizedSnapshotsNeverExposeTask() {
        Task visible = task("visible", 42L);
        Task hidden = task("hidden", 42L);
        when(taskService.listTasks(42L)).thenReturn(List.of(visible, hidden));
        allow("visible");
        when(snapshots.findComplete("hidden")).thenReturn(Optional.empty());
        String result = tool.forUser(42L, "").listRecentTasks();
        assertThat(result).contains("visible").doesNotContain("hidden", "共 2");
        when(taskService.getTaskDetail("hidden")).thenReturn(TaskDetailVO.builder().task(hidden).jobs(List.of()).build());
        assertThat(tool.forUser(42L, "").getTaskDetail("hidden")).doesNotContain("hidden", "job");
    }

    @Test
    void revokedGenerationAndUnexpectedJobAreDenied() {
        Task mine = task("mine", 42L);
        when(snapshots.findComplete("mine")).thenReturn(Optional.of(new TaskTargetSnapshot("mine",
                List.of(new TaskTargetSnapshot.Target("ag_1", 7L)))));
        Job job = new Job();
        job.setAgentId("other");
        job.setOutput("secret");
        when(taskService.getTaskDetail("mine")).thenReturn(TaskDetailVO.builder().task(mine).jobs(List.of(job)).build());
        var bound = tool.forUser(42L, "");
        assertThat(bound.getTaskDetail("mine")).doesNotContain("secret");
        when(acl.allowedForGeneration(42L, "ag_1", 7L, HostAclService.Permission.VIEW)).thenReturn(true);
        assertThat(bound.getTaskDetail("mine")).doesNotContain("secret");
    }

    @Test
    @DisplayName("listRecentTasks 没有上下文时拒绝查询")
    void listRecentTasks_withoutContext_isRefused() {
        String result = tool.listRecentTasks();

        assertThat(result).contains("拒绝");
        verifyNoInteractions(taskService);
    }

    @Test
    @DisplayName("目标主机不存在时不会创建任务（既有防护不回退）")
    void createTask_unknownAgent_isRejectedBeforeDispatch() {
        when(agentRepository.existsById("ag_missing")).thenReturn(false);

        tool = tool.forUser(42L, "10.0.0.1");
        String result = tool.createTask("df -h", "ag_missing", "x", 60);

        assertThat(result).contains("不存在");
        verifyNoInteractions(taskService);
    }

    @Test
    @DisplayName("timeoutSeconds 非法值回退默认值（既有行为不变）")
    void createTask_invalidTimeout_fallsBackToDefault() {
        when(agentRepository.existsById("ag_1")).thenReturn(true);
        when(taskService.createAndDispatch(any(TaskCreateRequest.class), anyLong()))
                .thenReturn(task("task_t", 42L));

        tool = tool.forUser(42L, "10.0.0.1");
        tool.createTask("echo hi", "ag_1", "x", 0);

        verify(taskService).createAndDispatch(
                org.mockito.ArgumentMatchers.argThat((TaskCreateRequest r) ->
                        r.getTimeoutSeconds() != null && r.getTimeoutSeconds() == 3600),
                eq(42L));
    }

    @Test
    @DisplayName("未指定目标主机时拒绝（既有防护不回退）")
    void createTask_noAgents_isRejected() {
        tool = tool.forUser(42L, "10.0.0.1");
        String result = tool.createTask("echo hi", "  ", "x", 60);

        assertThat(result).contains("未指定目标主机");
        verifyNoInteractions(taskService);
    }

    @Test
    @DisplayName("查询详情时任务不存在：返回失败信息而不泄露堆栈")
    void getTaskDetail_missingTask_reportsFailure() {
        when(taskService.getTaskDetail("task_gone"))
                .thenThrow(new BusinessException(404, "Task not found"));

        tool = tool.forUser(42L, "10.0.0.1");
        String result = tool.getTaskDetail("task_gone");

        assertThat(result).contains("查询任务详情失败");
    }
}
