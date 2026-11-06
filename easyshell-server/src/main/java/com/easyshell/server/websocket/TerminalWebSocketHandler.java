package com.easyshell.server.websocket;

import com.easyshell.server.config.WebSocketAuthInterceptor;
import java.net.URI;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class TerminalWebSocketHandler extends TextWebSocketHandler {

    private final AgentWebSocketHandler agentHandler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, WebSocketSession> sessionMap = new ConcurrentHashMap<>();
    private final Map<String, String> sessionToAgent = new ConcurrentHashMap<>();

    public TerminalWebSocketHandler(AgentWebSocketHandler agentHandler) {
        this.agentHandler = agentHandler;
        agentHandler.setTerminalHandler(this);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if (session.getAttributes().get(WebSocketAuthInterceptor.ATTR_USER_ID) == null) {
            log.warn("Terminal WebSocket without authenticated user, closing");
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        String agentId = extractAgentId(session);
        if (agentId == null) {
            session.close(CloseStatus.BAD_DATA);
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
        if (agentId != null) {
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
