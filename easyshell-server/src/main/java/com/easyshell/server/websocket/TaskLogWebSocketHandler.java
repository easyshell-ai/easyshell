package com.easyshell.server.websocket;

import com.easyshell.server.config.WebSocketAuthInterceptor;
import com.easyshell.server.model.entity.Task;
import com.easyshell.server.repository.TaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Component
public class TaskLogWebSocketHandler extends TextWebSocketHandler {

    /**
     * 与 TaskController.requireAccess 同一套 RBAC 语义：管理员可跨用户读取，
     * 其余角色只能读自己的任务。
     */
    private static final Set<String> ADMIN_ROLES = Set.of("admin", "super_admin");

    private final Map<String, CopyOnWriteArrayList<WebSocketSession>> taskSessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TaskRepository taskRepository;

    public TaskLogWebSocketHandler(TaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = (Long) session.getAttributes().get(WebSocketAuthInterceptor.ATTR_USER_ID);
        if (userId == null) {
            log.warn("Task log WebSocket without authenticated user, closing");
            closeQuietly(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        String taskId = extractTaskId(session);
        if (taskId == null) {
            log.warn("Task log WebSocket with unusable task id, closing");
            closeQuietly(session, CloseStatus.BAD_DATA);
            return;
        }

        // 认证不等于授权：握手通过只证明"登录了"，这里还要证明"这个 task 属于他"。
        //  task 不存在同样拒绝（fail-closed，不向探测方泄露 taskId 是否存在）。
        if (!canAccessTask(userId, roleOf(session), taskId)) {
            log.warn("Rejected task log WebSocket: user {} is not allowed to subscribe task {}",
                    userId, taskId);
            closeQuietly(session, CloseStatus.POLICY_VIOLATION);
            return;
        }

        taskSessions.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>()).add(session);
        log.debug("WebSocket connected for task: {}", taskId);
    }

    /**
     * 资源级授权：任务属主或管理员放行，其余一律拒绝。
     * 任何异常（仓库不可用等）按拒绝处理——安全通道不能因故障放行。
     */
    private boolean canAccessTask(Long userId, String role, String taskId) {
        try {
            Task task = taskRepository.findById(taskId).orElse(null);
            if (task == null) {
                return false;
            }
            return userId.equals(task.getCreatedBy()) || ADMIN_ROLES.contains(role);
        } catch (Exception e) {
            log.error("Task ownership check failed for task {}, denying subscription", taskId, e);
            return false;
        }
    }

    private static String roleOf(WebSocketSession session) {
        Object role = session.getAttributes().get(WebSocketAuthInterceptor.ATTR_ROLE);
        return role != null ? role.toString() : null;
    }

    private static void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (Exception ignored) {
            // best effort
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String taskId = extractTaskId(session);
        if (taskId != null) {
            var sessions = taskSessions.get(taskId);
            if (sessions != null) {
                sessions.remove(session);
                if (sessions.isEmpty()) {
                    taskSessions.remove(taskId);
                }
            }
        }
    }

    public void sendLog(String taskId, String jobId, String logLine) {
        var sessions = taskSessions.get(taskId);
        if (sessions == null || sessions.isEmpty()) return;

        try {
            Map<String, String> payload = Map.of(
                    "taskId", taskId,
                    "jobId", jobId,
                    "log", logLine
            );
            String json = objectMapper.writeValueAsString(payload);
            TextMessage message = new TextMessage(json);

            for (WebSocketSession session : sessions) {
                if (session.isOpen()) {
                    try {
                        session.sendMessage(message);
                    } catch (IOException e) {
                        log.warn("Failed to send log to session: {}", e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to serialize log message", e);
        }
    }

    private String extractTaskId(WebSocketSession session) {
        // Path only: the URI also carries the handshake token in its query string.
        java.net.URI uri = session.getUri();
        if (uri == null || uri.getPath() == null) {
            return null;
        }
        String path = uri.getPath();
        String last = path.substring(path.lastIndexOf('/') + 1);
        return last.isBlank() ? null : last;
    }
}
