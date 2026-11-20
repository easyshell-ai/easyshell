package com.easyshell.server.websocket;

import com.easyshell.server.config.WebSocketAuthInterceptor;
import com.easyshell.server.model.entity.Task;
import com.easyshell.server.model.entity.Job;
import com.easyshell.server.model.dto.TaskTargetSnapshot;
import com.easyshell.server.repository.JobRepository;
import com.easyshell.server.repository.TaskRepository;
import com.easyshell.server.repository.TaskTargetSnapshotRepository;
import com.easyshell.server.repository.UserRepository;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.service.HostAclService;
import com.easyshell.server.service.HostAclService.Permission;
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
    private final TaskTargetSnapshotRepository snapshotRepository;
    private final HostAclService hostAclService;
    private final UserRepository userRepository;
    private final JobRepository jobRepository;
    private final AuditLogService auditLogService;
    private final Map<WebSocketSession, TaskTargetSnapshot> bindings = new ConcurrentHashMap<>();

    public TaskLogWebSocketHandler(TaskRepository taskRepository, TaskTargetSnapshotRepository snapshotRepository,
                                   HostAclService hostAclService, UserRepository userRepository,
                                   JobRepository jobRepository, AuditLogService auditLogService) {
        this.taskRepository = taskRepository;
        this.snapshotRepository = snapshotRepository;
        this.hostAclService = hostAclService;
        this.userRepository = userRepository;
        this.jobRepository = jobRepository;
        this.auditLogService = auditLogService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = (Long) session.getAttributes().get(WebSocketAuthInterceptor.ATTR_USER_ID);
        if (userId == null) {
            log.warn("Task log WebSocket without authenticated user, closing");
            auditDenial(null, null);
            closeQuietly(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        String taskId = extractTaskId(session);
        if (taskId == null) {
            log.warn("Task log WebSocket with unusable task id, closing");
            auditDenial(userId, null);
            closeQuietly(session, CloseStatus.BAD_DATA);
            return;
        }

        // 认证不等于授权：握手通过只证明"登录了"，这里还要证明"这个 task 属于他"。
        //  task 不存在同样拒绝（fail-closed，不向探测方泄露 taskId 是否存在）。
        TaskTargetSnapshot snapshot = authorizedSnapshot(userId, taskId, null);
        if (snapshot == null) {
            log.warn("Rejected task log WebSocket: user {} is not allowed to subscribe task {}",
                    userId, taskId);
            auditDenial(userId, taskId);
            closeQuietly(session, CloseStatus.POLICY_VIOLATION);
            return;
        }

        bindings.put(session, snapshot);
        taskSessions.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>()).add(session);
        log.debug("WebSocket connected for task: {}", taskId);
    }

    /**
     * 资源级授权：任务属主或管理员放行，其余一律拒绝。
     * 任何异常（仓库不可用等）按拒绝处理——安全通道不能因故障放行。
     */
    private TaskTargetSnapshot authorizedSnapshot(Long userId, String taskId, String jobId) {
        try {
            if (userId == null) return null;
            var user = userRepository.findById(userId).orElse(null);
            if (user == null || !Integer.valueOf(1).equals(user.getStatus())
                    || !Set.of("viewer", "operator", "admin", "super_admin").contains(user.getRole())) return null;
            Task task = taskRepository.findById(taskId).orElse(null);
            if (task == null || !(userId.equals(task.getCreatedBy()) || ADMIN_ROLES.contains(user.getRole()))) {
                return null;
            }
            TaskTargetSnapshot snapshot = snapshotRepository.findComplete(taskId).orElse(null);
            if (snapshot == null || !taskId.equals(snapshot.taskId()) || snapshot.targets().isEmpty()
                    || task.getTotalCount() == null || task.getTotalCount() != snapshot.targets().size()) return null;
            Set<String> targetIds = snapshot.targets().stream().map(TaskTargetSnapshot.Target::agentId)
                    .collect(java.util.stream.Collectors.toSet());
            var jobs = jobRepository.findByTaskId(taskId);
            boolean found = false;
            for (Job job : jobs) {
                if (job == null || !taskId.equals(job.getTaskId()) || !targetIds.contains(job.getAgentId())) return null;
                if (jobId != null && jobId.equals(job.getId())) found = true;
            }
            if (jobId != null && !found) return null;
            for (var target : snapshot.targets()) {
                if (!hostAclService.allowedForGeneration(userId, target.agentId(), target.generation(), Permission.VIEW)) {
                    return null;
                }
            }
            return snapshot;
        } catch (Exception e) {
            log.warn("Task log authorization check failed for task {}, denying access", taskId);
            return null;
        }
    }

    private void auditDenial(Long userId, String taskId) {
        try {
            auditLogService.logSync(userId, "SYSTEM", "WS_ACCESS_DENIED", "task", taskId,
                    "unauthorized task log subscription or send", null, "denied");
        } catch (Exception e) {
            log.error("Task log WebSocket denial audit failed; access remains denied");
        }
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
        bindings.remove(session);
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
            for (WebSocketSession session : sessions) {
                if (session.isOpen()) {
                    TaskTargetSnapshot bound = bindings.get(session);
                    Object user = session.getAttributes().get(WebSocketAuthInterceptor.ATTR_USER_ID);
                    TaskTargetSnapshot current = jobId == null ? null : authorizedSnapshot(
                            user instanceof Long ? (Long) user : null, taskId, jobId);
                    if (bound == null || !bound.equals(current)) {
                        log.warn("Rejected task log WebSocket send: user {} is not allowed to subscribe task {}", user, taskId);
                        auditDenial(user instanceof Long ? (Long) user : null, taskId);
                        bindings.remove(session);
                        sessions.remove(session);
                        closeQuietly(session, CloseStatus.POLICY_VIOLATION);
                        continue;
                    }
                    try {
                        TextMessage message = new TextMessage(objectMapper.writeValueAsString(Map.of(
                                "taskId", taskId, "jobId", jobId, "log", logLine)));
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
