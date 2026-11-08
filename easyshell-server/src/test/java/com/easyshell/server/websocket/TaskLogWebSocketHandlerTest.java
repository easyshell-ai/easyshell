package com.easyshell.server.websocket;

import com.easyshell.server.config.WebSocketAuthInterceptor;
import com.easyshell.server.model.entity.Task;
import com.easyshell.server.repository.TaskRepository;
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

    private TaskLogWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        handler = new TaskLogWebSocketHandler(taskRepository);
    }

    private Task taskOwnedBy(Long userId) {
        Task task = new Task();
        task.setId(TASK_ID);
        task.setName("someone else's job");
        task.setScriptContent("echo secret");
        task.setCreatedBy(userId);
        return task;
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
}
