package com.easyshell.server.websocket;

import com.easyshell.server.config.WebSocketAuthInterceptor;
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
import java.net.URI;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class TerminalWebSocketHandler extends TextWebSocketHandler {

    private final AgentWebSocketHandler agentHandler;
    private final HostAclService hostAclService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, WebSocketSession> sessionMap = new ConcurrentHashMap<>();
    private final Map<String, String> sessionToAgent = new ConcurrentHashMap<>();
    private final Map<String, Binding> bindings = new ConcurrentHashMap<>();

    private record Binding(Long userId, String agentId, long generation) { }

    public TerminalWebSocketHandler(AgentWebSocketHandler agentHandler,
                                    HostAclService hostAclService,
                                    AuditLogService auditLogService) {
        this.agentHandler = agentHandler;
        this.hostAclService = hostAclService;
        this.auditLogService = auditLogService;
        agentHandler.setTerminalHandler(this);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Object principal = session.getAttributes().get(WebSocketAuthInterceptor.ATTR_USER_ID);
        Long userId = principal instanceof Long id ? id : null;
        String agentId = extractAgentId(session);
        if (userId == null) {
            denyAccess(session, null, agentId, "unauthenticated user");
            return;
        }

        if (agentId == null) {
            denyAccess(session, userId, null, "missing agent id");
            return;
        }

        OptionalLong generation;
        try {
            generation = hostAclService.authorizedGeneration(userId, agentId, Permission.TERMINAL);
        } catch (Exception e) {
            log.warn("Terminal WebSocket authorization failed for user {}: {}",
                    userId, e.getClass().getSimpleName());
            denyAccess(session, userId, agentId, "authorization failed");
            return;
        }
        if (generation.isEmpty()) {
            denyAccess(session, userId, agentId, "terminal access denied");
            return;
        }

        if (!agentHandler.isAgentConnected(agentId)) {
            session.sendMessage(new TextMessage("{\"type\":\"terminal_error\",\"data\":\"Agent not connected\"}"));
            session.close(CloseStatus.NORMAL);
            return;
        }

        String sessionId = "term_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        session.getAttributes().put("termSessionId", sessionId);

        sessionMap.put(sessionId, session);
        sessionToAgent.put(sessionId, agentId);
        bindings.put(sessionId, new Binding(userId, agentId, generation.getAsLong()));

        if (!revalidate(sessionId, session)) return;

        Map<String, Object> openMsg = Map.of(
                "type", "terminal_open",
                "sessionId", sessionId
        );
        boolean sent = agentHandler.sendToAgent(agentId, objectMapper.writeValueAsString(openMsg));
        if (!sent) {
            session.sendMessage(new TextMessage("{\"type\":\"terminal_error\",\"data\":\"Failed to open terminal on agent\"}"));
            session.close(CloseStatus.NORMAL);
            cleanup(sessionId);
            return;
        }

        // Wait for agent to confirm PTY is ready before notifying the browser
        log.info("Terminal session opened: {} for agent: {}, waiting for agent ready", sessionId, agentId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String sessionId = (String) session.getAttributes().get("termSessionId");
        if (sessionId == null) return;

        String agentId = sessionToAgent.get(sessionId);
        if (agentId == null) return;
        if (!revalidate(sessionId, session)) return;

        var node = objectMapper.readTree(message.getPayload());
        String type = node.has("type") ? node.get("type").asText() : "";

        Map<String, Object> forwardMsg;
        switch (type) {
            case "input" -> {
                String data = node.has("data") ? node.get("data").asText() : "";
                forwardMsg = Map.of(
                        "type", "terminal_input",
                        "sessionId", sessionId,
                        "data", data
                );
            }
            case "resize" -> {
                int cols = node.has("cols") ? node.get("cols").asInt() : 80;
                int rows = node.has("rows") ? node.get("rows").asInt() : 24;
                forwardMsg = Map.of(
                        "type", "terminal_resize",
                        "sessionId", sessionId,
                        "cols", cols,
                        "rows", rows
                );
            }
            default -> {
                return;
            }
        }

        agentHandler.sendToAgent(agentId, objectMapper.writeValueAsString(forwardMsg));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sessionId = (String) session.getAttributes().get("termSessionId");
        if (sessionId == null) return;

        String agentId = sessionToAgent.get(sessionId);
        if (agentId != null && revalidate(sessionId, session)) {
            try {
                Map<String, Object> closeMsg = Map.of(
                        "type", "terminal_close",
                        "sessionId", sessionId
                );
                agentHandler.sendToAgent(agentId, objectMapper.writeValueAsString(closeMsg));
            } catch (Exception e) {
                log.warn("Failed to send terminal close to agent", e);
            }
        }

        cleanup(sessionId);
        log.info("Terminal session closed: {}", sessionId);
    }

    public void handleAgentReady(String sessionId) {
        WebSocketSession session = sessionMap.get(sessionId);
        if (session == null || !session.isOpen()) return;
        if (!revalidate(sessionId, session)) return;

        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(
                    Map.of("type", "terminal_ready", "sessionId", sessionId)
            )));
            log.info("Terminal ready forwarded to browser: {}", sessionId);
        } catch (IOException e) {
            log.warn("Failed to send terminal_ready to browser: {}", e.getMessage());
        }
    }

    public void handleAgentOutput(String sessionId, String data) {
        WebSocketSession session = sessionMap.get(sessionId);
        if (session == null || !session.isOpen()) return;
        if (!revalidate(sessionId, session)) return;

        try {
            Map<String, Object> payload = Map.of(
                    "type", "output",
                    "data", data
            );
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
        } catch (IOException e) {
            log.warn("Failed to send terminal output to browser: {}", e.getMessage());
        }
    }

    public void handleAgentError(String sessionId, String error) {
        WebSocketSession session = sessionMap.get(sessionId);
        if (session == null || !session.isOpen()) return;
        if (!revalidate(sessionId, session)) return;

        try {
            Map<String, Object> payload = Map.of(
                    "type", "error",
                    "data", error
            );
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
        } catch (IOException e) {
            log.warn("Failed to send terminal error to browser: {}", e.getMessage());
        }
    }

    /**
     * A terminal session belongs to exactly one agent; used to stop an agent connection from
     * injecting output into sessions that were opened against a different agent.
     */
    public boolean isSessionOwnedByAgent(String sessionId, String agentId) {
        if (sessionId == null || agentId == null) {
            return false;
        }
        return agentId.equals(sessionToAgent.get(sessionId));
    }

    private void cleanup(String sessionId) {
        sessionMap.remove(sessionId);
        sessionToAgent.remove(sessionId);
        bindings.remove(sessionId);
    }

    private boolean revalidate(String sessionId, WebSocketSession session) {
        Binding binding = bindings.get(sessionId);
        if (binding == null) return false;
        try {
            if (hostAclService.allowedForGeneration(binding.userId(), binding.agentId(),
                    binding.generation(), Permission.TERMINAL)) return true;
        } catch (Exception e) {
            log.warn("Terminal WebSocket authorization recheck failed: {}", e.getClass().getSimpleName());
        }
        // Remove first: the close callback must not send anything to the agent after denial.
        cleanup(sessionId);
        denyAccess(session, binding.userId(), binding.agentId(), "terminal authorization lost");
        return false;
    }

    /** 关闭与审计独立执行，任一失败都不能导致终端被登记或打开。 */
    private void denyAccess(WebSocketSession session, Long userId, String agentId, String reason) {
        // audit_log.resource_id 与 agent.id 都是 64 字符，过长的非法路径不能阻止审计。
        String auditAgentId = agentId != null && agentId.length() <= 64 ? agentId : null;
        String logAgentId = auditAgentId == null ? "<invalid>"
                : auditAgentId.replaceAll("[\\p{Cntrl}\\p{Zl}\\p{Zp}]", "?");
        log.warn("Rejected terminal WebSocket: user={} agent={} reason={}", userId, logAgentId, reason);
        try {
            session.close(CloseStatus.POLICY_VIOLATION);
        } catch (Exception e) {
            log.warn("Failed to close rejected terminal WebSocket: {}", e.getClass().getSimpleName());
        }
        try {
            Object username = session.getAttributes().get(WebSocketAuthInterceptor.ATTR_USERNAME);
            String auditUsername = username instanceof String name && !name.isBlank() ? name : "SYSTEM";
            auditLogService.logSync(userId, auditUsername, "WS_ACCESS_DENIED", "agent", auditAgentId,
                    reason, null, "denied");
        } catch (Exception e) {
            log.error("Failed to audit rejected terminal WebSocket for user {}: {}",
                    userId, e.getClass().getSimpleName());
        }
    }

    private String extractAgentId(WebSocketSession session) {
        // The session URI includes the query string (the handshake token), so only the path
        // part may be used - otherwise "?token=..." ends up glued to the agent id.
        String path = pathOf(session.getUri());
        if (path == null) {
            return null;
        }
        String last = path.substring(path.lastIndexOf('/') + 1);
        if (last.isBlank()) {
            return null;
        }
        try {
            return java.net.URLDecoder.decode(last, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return last;
        }
    }

    private static String pathOf(java.net.URI uri) {
        if (uri == null) {
            return null;
        }
        try {
            return URI.create(uri.toString()).getPath();
        } catch (Exception e) {
            return uri.getPath();
        }
    }
}
