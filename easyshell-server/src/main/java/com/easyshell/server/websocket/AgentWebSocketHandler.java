package com.easyshell.server.websocket;

import com.easyshell.server.config.WebSocketAuthInterceptor;
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
import com.easyshell.server.service.FileProxyService;

import java.time.LocalDateTime;

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
    private FileProxyService fileProxyService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String agentId = extractAgentId(session);
        if (agentId == null) {
            try {
                session.close(CloseStatus.BAD_DATA);
            } catch (Exception ignored) {
                // best effort
            }
            return;
        }

        WebSocketSession existing = agentSessions.putIfAbsent(agentId, session);
        if (existing != null && existing.isOpen()) {
            // Another live connection already owns this agentId. Reject the newcomer instead of
            // silently hijacking the mapping (which used to evict the real agent permanently).
            log.warn("Rejecting duplicate agent connection for {} (session {})", agentId, session.getId());
            try {
                session.close(CloseStatus.POLICY_VIOLATION);
            } catch (Exception ignored) {
                // best effort
            }
            return;
        }
        if (existing != null) {
            // stale entry, replace it
            agentSessions.put(agentId, session);
        }
        log.info("Agent WebSocket connected: {}", agentId);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String agentId = extractAgentId(session);
        if (agentId == null) {
            return;
        }
        // Only drop the mapping if this session is still the registered one, otherwise a
        // reconnecting agent would be evicted by the closing of its predecessor.
        boolean wasOwner = agentSessions.remove(agentId, session);
        log.info("Agent WebSocket disconnected: {} (status={}, wasOwner={})", agentId, status, wasOwner);
        if (wasOwner) {
            // Auto-fail any running/pending jobs for this agent
            try {
                taskService.failRunningJobsForAgent(agentId,
                        "Agent disconnected (WebSocket closed: " + status + ")");
            } catch (Exception e) {
                log.error("Error failing jobs for disconnected agent {}", agentId, e);
            }
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String agentId = extractAgentId(session);
        try {
            JsonNode node = objectMapper.readTree(message.getPayload());
            String type = node.has("type") ? node.get("type").asText() : "";
            log.info("Agent message received: agent={}, type={}, payload_length={}", agentId, type, message.getPayloadLength());

            switch (type) {
                case "log" -> {
                    String jobId = node.get("jobId").asText();
                    String logLine = node.get("log").asText();
                    log.debug("Agent {} log for job {}: {}", agentId, jobId, logLine);
                    taskService.appendJobLog(jobId, logLine, agentId);
                }
                case "result" -> {
                    String jobId = node.get("jobId").asText();
                    int status = node.get("status").asInt();
                    log.info("Agent {} result for job {}: status={}", agentId, jobId, status);
                    JobResultRequest result = JobResultRequest.builder()
                            .jobId(jobId)
                            .status(status)
                            .exitCode(node.has("exitCode") ? node.get("exitCode").asInt() : null)
                            .output(node.has("output") ? node.get("output").asText() : null)
                            .build();
                    taskService.reportJobResult(result, agentId);
                }
                case "terminal_output" -> {
                    String sessionId = node.has("sessionId") ? node.get("sessionId").asText() : "";
                    String data = node.has("data") ? node.get("data").asText() : "";
                    if (terminalHandler != null && terminalHandler.isSessionOwnedByAgent(sessionId, agentId)) {
                        terminalHandler.handleAgentOutput(sessionId, data);
                    } else {
                        log.warn("Ignoring terminal_output for session {} not owned by agent {}", sessionId, agentId);
                    }
                }
                case "terminal_ready" -> {
                    String sessionId = node.has("sessionId") ? node.get("sessionId").asText() : "";
                    if (terminalHandler != null && terminalHandler.isSessionOwnedByAgent(sessionId, agentId)) {
                        terminalHandler.handleAgentReady(sessionId);
                    } else {
                        log.warn("Ignoring terminal_ready for session {} not owned by agent {}", sessionId, agentId);
                    }
                }
                case "terminal_error" -> {
                    String sessionId = node.has("sessionId") ? node.get("sessionId").asText() : "";
                    String data = node.has("data") ? node.get("data").asText() : "";
                    if (terminalHandler != null && terminalHandler.isSessionOwnedByAgent(sessionId, agentId)) {
                        terminalHandler.handleAgentError(sessionId, data);
                    } else {
                        log.warn("Ignoring terminal_error for session {} not owned by agent {}", sessionId, agentId);
                    }
                }
                case "file_list_result", "file_download_chunk", "file_result" -> {
                    String requestId = node.has("requestId") ? node.get("requestId").asText() : "";
                    if (fileProxyService != null && !requestId.isEmpty()) {
                        fileProxyService.handleFileResponse(requestId, message.getPayload());
                    }
                }
                default -> log.warn("Unknown message type from agent {}: {}", agentId, type);
            }
        } catch (Exception e) {
            log.error("Error processing agent {} message: {}", agentId, e.getMessage(), e);
        }
    }

    public boolean dispatchJob(String agentId, Job job, String scriptContent, int timeoutSeconds) {
        WebSocketSession session = agentSessions.get(agentId);
        if (session == null || !session.isOpen()) {
            return false;
        }

        try {
            // Save job status BEFORE sending WebSocket message to avoid race condition:
            // if agent responds extremely fast, reportJobResult could process the result
            // before this save completes, leading to started_at=NULL
            job.setStatus(1);
            job.setStartedAt(LocalDateTime.now());
            jobRepository.save(job);

            Map<String, Object> payload = Map.of(
                    "type", "execute",
                    "jobId", job.getId(),
                    "taskId", job.getTaskId(),
                    "script", scriptContent,
                    "timeout", timeoutSeconds
            );
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
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

    public void setFileProxyService(FileProxyService service) {
        this.fileProxyService = service;
    }

    private String extractAgentId(WebSocketSession session) {
        Object attr = session.getAttributes().get(WebSocketAuthInterceptor.ATTR_AGENT_ID);
        if (attr instanceof String agentId && !agentId.isBlank()) {
            return agentId;
        }
        // Fallback for sessions established outside the interceptor (tests). Only the path
        // part may be used: the URI carries the query string too.
        java.net.URI uri = session.getUri();
        if (uri == null || uri.getPath() == null) {
            return null;
        }
        String path = uri.getPath();
        String last = path.substring(path.lastIndexOf('/') + 1);
        return last.isBlank() ? null : last;
    }
}
