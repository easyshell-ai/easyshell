package com.easyshell.server.websocket;

import com.easyshell.server.config.WebSocketAuthInterceptor;
import com.easyshell.server.model.entity.Workflow;
import com.easyshell.server.model.entity.WorkflowRun;
import com.easyshell.server.repository.UserRepository;
import com.easyshell.server.repository.WorkflowRepository;
import com.easyshell.server.repository.WorkflowRunRepository;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.workflow.engine.WorkflowEvent;
import com.easyshell.server.workflow.service.WorkflowRunProvenanceService;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 工作流运行实时通道（设计 §6.4：{@code /ws/workflow/run/{runId}}）。
 *
 * <p>方向：服务端 → 客户端推 {@code RUN_STARTED / NODE_STARTED / NODE_SUCCEEDED /
 * NODE_FAILED / NODE_SKIPPED / NODE_WAITING / NODE_CANCELLED / APPROVAL_REQUESTED /
 * APPROVAL_DECIDED / RUN_WAITING / RUN_SUCCEEDED / RUN_FAILED / RUN_CANCELLED /
 * RUN_TIMEOUT}（引擎事件的超集，原样透传）；客户端 → 服务端只认 {@code PING}（回 PONG）。
 * <b>节点日志不在这里重造</b>：script-exec 的 agent 原始日志仍走
 * {@code /ws/task/log/{taskId}，监控页按 nodeRun.taskId 分别建连。</p>
 *
 * <p><b>资源级授权（B4 / C-12 硬要求）</b>：握手通过只证明"登录了"，
 * {@link #afterConnectionEstablished} 还要证明"这个 run 属于他"——
 * 属主（startedBy）或工作流创建者或 admin 放行；run 不存在 / 查询异常一律拒绝
 * （fail-closed，不向探测方泄露 runId 是否存在），越权关闭为
 * {@link CloseStatus#POLICY_VIOLATION} 并记 WARN 审计。范式与 1.1.2 修好的
 * {@link TaskLogWebSocketHandler} 完全一致。</p>
 */
@Slf4j
@Component
public class WorkflowRunWebSocketHandler extends TextWebSocketHandler {

    /** 与 TaskLogWebSocketHandler / WorkflowAccess 同一套 RBAC 语义 */
    private static final List<String> ADMIN_ROLES = List.of("admin", "super_admin");
    private static final List<String> VALID_ROLES = List.of("viewer", "operator", "admin", "super_admin");

    private final Map<String, CopyOnWriteArrayList<WebSocketSession>> runSessions = new ConcurrentHashMap<>();

    private final WorkflowRunRepository runRepository;
    private final UserRepository userRepository;
    private final WorkflowRepository workflowRepository;
    private final AuditLogService auditLogService;
    @Autowired
    private WorkflowRunProvenanceService provenanceService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    public WorkflowRunWebSocketHandler(WorkflowRunRepository runRepository,
                                       WorkflowRepository workflowRepository,
                                       AuditLogService auditLogService,
                                       UserRepository userRepository) {
        this.runRepository = runRepository;
        this.workflowRepository = workflowRepository;
        this.auditLogService = auditLogService;
        this.userRepository = userRepository;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = (Long) session.getAttributes().get(WebSocketAuthInterceptor.ATTR_USER_ID);
        if (userId == null) {
            log.warn("Workflow run WebSocket without authenticated user, closing");
            closeQuietly(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        String runId = extractRunId(session);
        if (runId == null) {
            log.warn("Workflow run WebSocket with unusable run id, closing");
            closeQuietly(session, CloseStatus.BAD_DATA);
            return;
        }
        if (!canAccessRun(userId, roleOf(session), runId)) {
            log.warn("Rejected workflow run WebSocket: user {} is not allowed to subscribe run {}",
                    userId, runId);
            auditDenial(userId, runId);
            closeQuietly(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        runSessions.computeIfAbsent(runId, k -> new CopyOnWriteArrayList<>()).add(session);
        log.debug("WebSocket connected for run: {}", runId);
    }

    /**
     * 资源级授权：run 的触发人 / 所属工作流创建者 / 管理员放行，其余一律拒绝。
     * 任何异常（仓库不可用等）按拒绝处理——安全通道不能因故障放行。
     */
    private boolean canAccessRun(Long userId, String role, String runId) {
        try {
            var user = userRepository.findById(userId).orElse(null);
            if (user == null || !Integer.valueOf(1).equals(user.getStatus())
                    || user.getRole() == null || !VALID_ROLES.contains(user.getRole())) {
                return false;
            }
            WorkflowRun run = runRepository.findById(runId).orElse(null);
            if (run == null) {
                return false;
            }
            boolean admin = ADMIN_ROLES.contains(role) && ADMIN_ROLES.contains(user.getRole());
            if (!userId.equals(run.getStartedBy()) && !admin) {
                Workflow workflow = workflowRepository.findById(run.getWorkflowId()).orElse(null);
                if (workflow == null || !userId.equals(workflow.getCreatedBy())) {
                    return false;
                }
            }
            return provenanceService.canView(userId, runId);
        } catch (Exception e) {
            log.error("Run ownership check failed for run {}, denying subscription", runId, e);
            return false;
        }
    }

    /** 越权审计（§6.4：WARN + WS_ACCESS_DENIED；写失败不能阻止关闭） */
    private void auditDenial(Long userId, String runId) {
        try {
            auditLogService.logSync(userId, "SYSTEM", "WS_ACCESS_DENIED", "workflow_run", runId,
                    "unauthorized workflow run subscription", null, "denied");
        } catch (Exception e) {
            log.error("WS 越权审计写失败（仍关闭连接）: user={} run={} err={}", userId, runId, e.getMessage());
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        if ("PING".equalsIgnoreCase(message.getPayload())) {
            if (!reauthorize(session, extractRunId(session))) return;
            session.sendMessage(new TextMessage("PONG"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String runId = extractRunId(session);
        if (runId != null) {
            var sessions = runSessions.get(runId);
            if (sessions != null) {
                sessions.remove(session);
                if (sessions.isEmpty()) {
                    runSessions.remove(runId);
                }
            }
        }
    }

    /** 推送一个工作流事件（{@code WorkflowEventBroadcaster} 的唯一入口） */
    public void broadcast(WorkflowEvent event) {
        var sessions = runSessions.get(event.runId());
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        try {
            Map<String, Object> payload = Map.of(
                    "type", event.type(),
                    "runId", event.runId(),
                    "nodeKey", event.nodeKey() == null ? "" : event.nodeKey(),
                    "message", event.message() == null ? "" : event.message(),
                    "at", event.at().toString());
            TextMessage text = new TextMessage(objectMapper.writeValueAsString(payload));
            for (WebSocketSession session : sessions) {
                if (session.isOpen()) {
                    try {
                        if (!reauthorize(session, event.runId())) continue;
                        session.sendMessage(text);
                    } catch (IOException e) {
                        log.warn("Failed to push workflow event to session: {}", e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to serialize workflow event", e);
        }
    }

    private boolean reauthorize(WebSocketSession session, String runId) {
        Object principal = session.getAttributes().get(WebSocketAuthInterceptor.ATTR_USER_ID);
        Long userId = principal instanceof Long ? (Long) principal : null;
        if (userId != null && runId != null && canAccessRun(userId, roleOf(session), runId)) return true;
        log.warn("Revoked workflow run WebSocket: user {} run {}", userId, runId);
        auditDenial(userId, runId);
        var sessions = runId == null ? null : runSessions.get(runId);
        if (sessions != null) sessions.remove(session);
        closeQuietly(session, CloseStatus.POLICY_VIOLATION);
        return false;
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

    private String extractRunId(WebSocketSession session) {
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
