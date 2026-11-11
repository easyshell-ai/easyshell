package com.easyshell.server.websocket;

import com.easyshell.server.config.WebSocketAuthInterceptor;
import com.easyshell.server.model.entity.Task;
import com.easyshell.server.model.entity.Job;
import com.easyshell.server.model.entity.User;
import com.easyshell.server.model.dto.TaskTargetSnapshot;
import com.easyshell.server.repository.TaskRepository;
import com.easyshell.server.repository.TaskTargetSnapshotRepository;
import com.easyshell.server.repository.JobRepository;
import com.easyshell.server.repository.UserRepository;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.service.HostAclService;
import com.easyshell.server.service.HostAclService.Permission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B4 存量越权回归：/ws/task/log/{taskId} 曾只校验"已登录"，
 * 任何登录用户遍历 taskId 即可订阅他人任务日志。修复后必须做资源归属校验。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TaskLogWebSocketHandler — 订阅任务日志的资源级授权")
class TaskLogWebSocketHandlerTest {

    private static final String TASK_ID = "task_owned_by_42";
    private static final String PATH = "/ws/task/log/" + TASK_ID + "?token=fake";

    @Mock
    private TaskRepository taskRepository;
    @Mock
    private TaskTargetSnapshotRepository snapshotRepository;
    @Mock
    private HostAclService hostAclService;
    @Mock private UserRepository userRepository;
    @Mock private JobRepository jobRepository;
    @Mock private AuditLogService auditLogService;

    private TaskLogWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        handler = new TaskLogWebSocketHandler(taskRepository, snapshotRepository, hostAclService,
                userRepository, jobRepository, auditLogService);
        lenient().when(userRepository.findById(org.mockito.ArgumentMatchers.anyLong())).thenAnswer(invocation -> {
            long id = invocation.getArgument(0);
            User user = new User();
            user.setId(id);
            user.setRole(id == 7L ? "super_admin" : "operator");
            return Optional.of(user);
        });
        lenient().when(jobRepository.findByTaskId(TASK_ID)).thenReturn(List.of(job("job_1", TASK_ID, "agent-1"),
                job("job", TASK_ID, "agent-1")));
        lenient().when(snapshotRepository.findComplete(TASK_ID)).thenReturn(Optional.of(snapshot()));
        lenient().when(hostAclService.allowedForGeneration(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.eq("agent-1"), org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(Permission.VIEW))).thenReturn(true);
    }

    private TaskTargetSnapshot snapshot() {
        return new TaskTargetSnapshot(TASK_ID, List.of(new TaskTargetSnapshot.Target("agent-1", 1L)));
    }

    private Task taskOwnedBy(Long userId) {
        Task task = new Task();
        task.setId(TASK_ID);
        task.setName("someone else's job");
        task.setScriptContent("echo secret");
        task.setCreatedBy(userId);
        task.setTotalCount(1);
        return task;
    }

    private Job job(String id, String taskId, String agentId) {
        Job job = new Job();
        job.setId(id);
        job.setTaskId(taskId);
        job.setAgentId(agentId);
        return job;
    }

    private WebSocketSession session(Long userId, String role) throws Exception {
        WebSocketSession session = org.mockito.Mockito.mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        if (userId != null) {
            attributes.put(WebSocketAuthInterceptor.ATTR_USER_ID, userId);
        }
        if (role != null) {
            attributes.put(WebSocketAuthInterceptor.ATTR_ROLE, role);
        }
        lenient().when(session.getAttributes()).thenReturn(attributes);
        lenient().when(session.getUri()).thenReturn(new URI(PATH));
        lenient().when(session.isOpen()).thenReturn(true);
        return session;
    }

    @Test
    @DisplayName("属主订阅放行，且能收到该任务的日志推送")
    void owner_isAllowedAndReceivesLogs() throws Exception {
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(taskOwnedBy(42L)));
        WebSocketSession session = session(42L, "operator");

        handler.afterConnectionEstablished(session);

        verify(session, never()).close(any());
        handler.sendLog(TASK_ID, "job_1", "line-one");
        verify(session).sendMessage(org.mockito.ArgumentMatchers.argThat(
                (TextMessage m) -> m.getPayload().contains("line-one")));
    }

    @Test
    @DisplayName("越权订阅被拒：会话以 POLICY_VIOLATION 关闭，不注册、收不到日志")
    void nonOwner_isRejectedWithPolicyViolation() throws Exception {
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(taskOwnedBy(42L)));
        WebSocketSession attacker = session(99L, "operator");

        handler.afterConnectionEstablished(attacker);

        verify(attacker).close(CloseStatus.POLICY_VIOLATION);
        handler.sendLog(TASK_ID, "job_1", "secret-line");
        verify(attacker, never()).sendMessage(any());
    }

    @Test
    @DisplayName("管理员可订阅他人任务（与 TaskController.requireAccess 同语义）")
    void admin_isAllowedToSubscribeOthersTask() throws Exception {
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(taskOwnedBy(42L)));
        WebSocketSession admin = session(7L, "super_admin");

        handler.afterConnectionEstablished(admin);

        verify(admin, never()).close(any());
        handler.sendLog(TASK_ID, "job_1", "admin-line");
        verify(admin).sendMessage(org.mockito.ArgumentMatchers.argThat(
                (TextMessage m) -> m.getPayload().contains("admin-line")));
    }

    @Test
    @DisplayName("operator 越权订阅同样拒绝（只有 admin/super_admin 能跨用户）")
    void operator_cannotSubscribeOthersTask() throws Exception {
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(taskOwnedBy(42L)));
        WebSocketSession operator = session(99L, "operator");

        handler.afterConnectionEstablished(operator);

        verify(operator).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    @DisplayName("taskId 不存在时同样拒绝（fail-closed，不泄露任务是否存在）")
    void unknownTask_isRejected() throws Exception {
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.empty());
        WebSocketSession session = session(42L, "operator");

        handler.afterConnectionEstablished(session);

        verify(session).close(CloseStatus.POLICY_VIOLATION);
        handler.sendLog(TASK_ID, "job_1", "line");
        verify(session, never()).sendMessage(any());
    }

    @Test
    @DisplayName("归属查询抛异常时拒绝订阅（fail-closed）")
    void repositoryFailure_deniesSubscription() throws Exception {
        when(taskRepository.findById(TASK_ID)).thenThrow(new RuntimeException("db down"));
        WebSocketSession session = session(42L, "operator");

        handler.afterConnectionEstablished(session);

        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    @DisplayName("未认证会话（无 ATTR_USER_ID）仍然拒绝")
    void unauthenticated_isRejected() throws Exception {
        WebSocketSession session = session(null, null);

        handler.afterConnectionEstablished(session);

        verify(session).close(CloseStatus.POLICY_VIOLATION);
        verify(taskRepository, never()).findById(any());
    }

    @Test
    @DisplayName("属主关闭连接后不再收到日志")
    void owner_disconnect_stopsReceivingLogs() throws Exception {
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(taskOwnedBy(42L)));
        WebSocketSession session = session(42L, "operator");

        handler.afterConnectionEstablished(session);
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        handler.sendLog(TASK_ID, "job_1", "after-close");

        verify(session, never()).sendMessage(any());
        assertThat(handler).isNotNull();
    }

    @Test
    void unknownSnapshotRejectsEvenAdmin() throws Exception {
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(taskOwnedBy(42L)));
        when(snapshotRepository.findComplete(TASK_ID)).thenReturn(Optional.empty());
        WebSocketSession admin = session(7L, "admin");
        handler.afterConnectionEstablished(admin);
        verify(admin).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void deniedViewOnAnyTargetRejects() throws Exception {
        Task task = taskOwnedBy(42L);
        task.setTotalCount(2);
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));
        when(snapshotRepository.findComplete(TASK_ID)).thenReturn(Optional.of(new TaskTargetSnapshot(TASK_ID,
                List.of(new TaskTargetSnapshot.Target("agent-1", 1L), new TaskTargetSnapshot.Target("agent-2", 2L)))));
        WebSocketSession session = session(42L, "operator");
        handler.afterConnectionEstablished(session);
        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void revokedViewClosesBeforeSending() throws Exception {
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(taskOwnedBy(42L)));
        when(hostAclService.allowedForGeneration(42L, "agent-1", 1L, Permission.VIEW)).thenReturn(true, false);
        WebSocketSession session = session(42L, "operator");
        handler.afterConnectionEstablished(session);
        handler.sendLog(TASK_ID, "job", "secret");
        verify(session).close(CloseStatus.POLICY_VIOLATION);
        verify(session, never()).sendMessage(any());
    }

    @Test
    void snapshotDatabaseFailureClosesBeforeSending() throws Exception {
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(taskOwnedBy(42L)));
        when(snapshotRepository.findComplete(TASK_ID)).thenReturn(Optional.of(snapshot()))
                .thenThrow(new RuntimeException("db down"));
        WebSocketSession session = session(42L, "operator");
        handler.afterConnectionEstablished(session);
        handler.sendLog(TASK_ID, "job", "secret");
        verify(session).close(CloseStatus.POLICY_VIOLATION);
        verify(session, never()).sendMessage(any());
    }

    @Test
    void changedSnapshotClosesBeforeSendingEvenIfNewTargetIsAllowed() throws Exception {
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(taskOwnedBy(42L)));
        var changed = new TaskTargetSnapshot(TASK_ID, List.of(new TaskTargetSnapshot.Target("agent-1", 2L)));
        when(snapshotRepository.findComplete(TASK_ID)).thenReturn(Optional.of(snapshot()), Optional.of(changed));
        when(hostAclService.allowedForGeneration(42L, "agent-1", 2L, Permission.VIEW)).thenReturn(true);
        WebSocketSession session = session(42L, "operator");
        handler.afterConnectionEstablished(session);
        handler.sendLog(TASK_ID, "job", "secret");
        verify(session).close(CloseStatus.POLICY_VIOLATION);
        verify(session, never()).sendMessage(any());
    }

    @Test
    void missingTargetRejectsAndAudits() throws Exception {
        Task task = taskOwnedBy(42L);
        task.setTotalCount(2);
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));
        WebSocketSession session = session(42L, "operator");
        handler.afterConnectionEstablished(session);
        verify(session).close(CloseStatus.POLICY_VIOLATION);
        verify(auditLogService).logSync(org.mockito.ArgumentMatchers.eq(42L), any(), any(), any(),
                org.mockito.ArgumentMatchers.eq(TASK_ID), any(), org.mockito.ArgumentMatchers.isNull(), any());
    }

    @Test
    void foreignTargetJobRejectsSubscription() throws Exception {
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(taskOwnedBy(42L)));
        when(jobRepository.findByTaskId(TASK_ID)).thenReturn(List.of(job("bad", TASK_ID, "other")));
        WebSocketSession session = session(42L, "operator");
        handler.afterConnectionEstablished(session);
        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void crossTaskJobClosesBeforeSend() throws Exception {
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(taskOwnedBy(42L)));
        WebSocketSession session = session(42L, "operator");
        handler.afterConnectionEstablished(session);
        handler.sendLog(TASK_ID, "foreign-job", "secret");
        verify(session).close(CloseStatus.POLICY_VIOLATION);
        verify(session, never()).sendMessage(any());
    }

    @Test
    void demotedAdminWithOldTokenIsRejectedBeforeSend() throws Exception {
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(taskOwnedBy(42L)));
        User admin = new User();
        admin.setRole("admin");
        User demoted = new User();
        demoted.setRole("operator");
        when(userRepository.findById(7L)).thenReturn(Optional.of(admin), Optional.of(demoted));
        WebSocketSession session = session(7L, "admin");
        handler.afterConnectionEstablished(session);
        handler.sendLog(TASK_ID, "job_1", "secret");
        verify(session).close(CloseStatus.POLICY_VIOLATION);
        verify(session, never()).sendMessage(any());
    }

    @Test
    void disabledUserAndAuditFailureStillClose() throws Exception {
        User disabled = new User();
        disabled.setStatus(0);
        when(userRepository.findById(42L)).thenReturn(Optional.of(disabled));
        org.mockito.Mockito.doThrow(new RuntimeException("audit down")).when(auditLogService)
                .logSync(any(), any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.isNull(), any());
        WebSocketSession session = session(42L, "operator");
        handler.afterConnectionEstablished(session);
        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void jobDatabaseFailureClosesBeforeSend() throws Exception {
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(taskOwnedBy(42L)));
        when(jobRepository.findByTaskId(TASK_ID)).thenReturn(List.of(job("job_1", TASK_ID, "agent-1")))
                .thenThrow(new RuntimeException("db down"));
        WebSocketSession session = session(42L, "operator");
        handler.afterConnectionEstablished(session);
        handler.sendLog(TASK_ID, "job_1", "secret");
        verify(session).close(CloseStatus.POLICY_VIOLATION);
        verify(session, never()).sendMessage(any());
    }

    @Test
    void pendingTaskWithoutJobsCannotEmitLogs() throws Exception {
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(taskOwnedBy(42L)));
        when(jobRepository.findByTaskId(TASK_ID)).thenReturn(List.of());
        WebSocketSession session = session(42L, "operator");
        handler.afterConnectionEstablished(session);
        verify(session, never()).close(any());
        handler.sendLog(TASK_ID, "job_1", "secret");
        verify(session).close(CloseStatus.POLICY_VIOLATION);
        verify(session, never()).sendMessage(any());
    }

    @Test
    void staleAdminRoleAtHandshakeDoesNotGrantAccess() throws Exception {
        User demoted = new User();
        demoted.setRole("operator");
        when(userRepository.findById(7L)).thenReturn(Optional.of(demoted));
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(taskOwnedBy(42L)));
        WebSocketSession session = session(7L, "admin");
        handler.afterConnectionEstablished(session);
        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }
}
