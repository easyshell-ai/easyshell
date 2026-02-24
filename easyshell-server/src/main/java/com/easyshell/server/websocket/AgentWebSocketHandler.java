package com.easyshell.server.websocket;

import com.easyshell.server.model.dto.JobResultRequest;
import com.easyshell.server.model.entity.Job;
import com.easyshell.server.repository.JobRepository;
import com.easyshell.server.service.TaskService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentWebSocketHandler extends TextWebSocketHandler {

    private final TaskService taskService;
    private final JobRepository jobRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, WebSocketSession> agentSessions = new ConcurrentHashMap<>();
    private TerminalWebSocketHandler terminalHandler;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String agentId = extractAgentId(session);
        if (agentId != null) {
            agentSessions.put(agentId, session);
            log.info("Agent WebSocket connected: {}", agentId);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String agentId = extractAgentId(session);
        if (agentId != null) {
            agentSessions.remove(agentId);
            log.info("Agent WebSocket disconnected: {}", agentId);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            JsonNode node = objectMapper.readTree(message.getPayload());
            String type = node.has("type") ? node.get("type").asText() : "";

            switch (type) {
                case "log" -> {
                    String jobId = node.get("jobId").asText();
                    String logLine = node.get("log").asText();
                    taskService.appendJobLog(jobId, logLine);
                }
                case "result" -> {
                    JobResultRequest result = JobResultRequest.builder()
                            .jobId(node.get("jobId").asText())
                            .status(node.get("status").asInt())
                            .exitCode(node.has("exitCode") ? node.get("exitCode").asInt() : null)
                            .output(node.has("output") ? node.get("output").asText() : null)
                            .build();
                    taskService.reportJobResult(result);
                }
                case "terminal_output" -> {
                    String sessionId = node.has("sessionId") ? node.get("sessionId").asText() : "";
                    String data = node.has("data") ? node.get("data").asText() : "";
                    if (terminalHandler != null) {
                        terminalHandler.handleAgentOutput(sessionId, data);
                    }
                }
                case "terminal_ready" -> {
                    String sessionId = node.has("sessionId") ? node.get("sessionId").asText() : "";
                    if (terminalHandler != null) {
                        terminalHandler.handleAgentReady(sessionId);
                    }
                }
                case "terminal_error" -> {
                    String sessionId = node.has("sessionId") ? node.get("sessionId").asText() : "";
                    String data = node.has("data") ? node.get("data").asText() : "";
                    if (terminalHandler != null) {
                        terminalHandler.handleAgentError(sessionId, data);
                    }
                }
                default -> log.warn("Unknown message type from agent: {}", type);
            }
        } catch (Exception e) {
            log.error("Error processing agent message", e);
        }
    }

    public boolean dispatchJob(String agentId, Job job, String scriptContent, int timeoutSeconds) {
        WebSocketSession session = agentSessions.get(agentId);
        if (session == null || !session.isOpen()) {
            return false;
        }

        try {
            Map<String, Object> payload = Map.of(
                    "type", "execute",
                    "jobId", job.getId(),
                    "taskId", job.getTaskId(),
                    "script", scriptContent,
                    "timeout", timeoutSeconds
            );
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
            job.setStatus(1);
            job.setStartedAt(LocalDateTime.now());
            jobRepository.save(job);
            return true;
        } catch (Exception e) {
            log.error("Failed to dispatch job {} to agent {}", job.getId(), agentId, e);
            return false;
        }
    }

    public boolean isAgentConnected(String agentId) {
        WebSocketSession session = agentSessions.get(agentId);
        return session != null && session.isOpen();
    }

    public List<String> getConnectedAgentIds() {
        return agentSessions.entrySet().stream()
                .filter(e -> e.getValue().isOpen())
                .map(Map.Entry::getKey)
                .toList();
    }

    public boolean sendToAgent(String agentId, String jsonMessage) {
        WebSocketSession session = agentSessions.get(agentId);
        if (session == null || !session.isOpen()) {
            return false;
        }
        try {
            session.sendMessage(new TextMessage(jsonMessage));
            return true;
        } catch (Exception e) {
            log.error("Failed to send message to agent {}", agentId, e);
            return false;
        }
    }

    public void setTerminalHandler(TerminalWebSocketHandler handler) {
        this.terminalHandler = handler;
    }

    private String extractAgentId(WebSocketSession session) {
        String uri = session.getUri() != null ? session.getUri().toString() : "";
        String[] parts = uri.split("/");
        if (parts.length > 0) {
            return parts[parts.length - 1];
        }
        return null;
    }
}
