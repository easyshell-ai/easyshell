package com.easyshell.server.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Component
public class TaskLogWebSocketHandler extends TextWebSocketHandler {

    private final Map<String, CopyOnWriteArrayList<WebSocketSession>> taskSessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String taskId = extractTaskId(session);
        if (taskId != null) {
            taskSessions.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>()).add(session);
            log.debug("WebSocket connected for task: {}", taskId);
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
        String uri = session.getUri() != null ? session.getUri().toString() : "";
        String[] parts = uri.split("/");
        if (parts.length > 0) {
            return parts[parts.length - 1];
        }
        return null;
    }
}
