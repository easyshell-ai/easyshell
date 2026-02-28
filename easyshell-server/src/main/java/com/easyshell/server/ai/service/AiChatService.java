package com.easyshell.server.ai.service;

import com.easyshell.server.ai.chat.SystemPrompts;
import com.easyshell.server.ai.agent.AgentDefinition;
import com.easyshell.server.ai.agent.AgentDefinitionRepository;
import com.easyshell.server.ai.agent.DelegateTaskTool;
import com.easyshell.server.ai.agent.GetTaskResultTool;
import com.easyshell.server.ai.config.AgenticConfigService;
import com.easyshell.server.ai.memory.SessionSummarizer;
import com.easyshell.server.ai.model.dto.AiChatRequest;
import com.easyshell.server.ai.model.entity.AiChatMessage;
import com.easyshell.server.ai.model.entity.AiChatSession;
import com.easyshell.server.ai.model.vo.AiChatMessageVO;
import com.easyshell.server.ai.model.vo.AiChatResponseVO;
import com.easyshell.server.ai.model.vo.AiChatSessionVO;
import com.easyshell.server.ai.orchestrator.AgentEvent;
import com.easyshell.server.ai.orchestrator.OrchestratorEngine;
import com.easyshell.server.ai.orchestrator.OrchestratorRequest;
import com.easyshell.server.ai.orchestrator.TokenEstimator;
import com.easyshell.server.ai.repository.AiChatMessageRepository;
import com.easyshell.server.ai.repository.AiChatSessionRepository;
import com.easyshell.server.ai.security.AiQuotaService;
import com.easyshell.server.ai.security.SensitiveDataFilter;
import com.easyshell.server.ai.tool.*;
import com.easyshell.server.model.entity.Agent;
import com.easyshell.server.repository.AgentRepository;
import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatService {

    private final ChatModelFactory chatModelFactory;
    private final AiChatSessionRepository sessionRepository;
    private final AiChatMessageRepository messageRepository;
    private final HostListTool hostListTool;
    private final HostTagTool hostTagTool;
    private final ScriptExecuteTool scriptExecuteTool;
    private final SoftwareDetectTool softwareDetectTool;
    private final TaskManageTool taskManageTool;
    private final ScriptManageTool scriptManageTool;
    private final ClusterManageTool clusterManageTool;
    private final MonitoringTool monitoringTool;
    private final AuditQueryTool auditQueryTool;
    private final ScheduledTaskTool scheduledTaskTool;
    private final SubAgentTool subAgentTool;
    private final DelegateTaskTool delegateTaskTool;
    private final GetTaskResultTool getTaskResultTool;
    private final SensitiveDataFilter sensitiveDataFilter;
    private final AiQuotaService quotaService;
    private final AuditLogService auditLogService;
    private final AgentRepository agentRepository;
    private final OrchestratorEngine orchestratorEngine;
    private final AgenticConfigService agenticConfigService;
    private final TokenEstimator tokenEstimator;
    private final SessionSummarizer sessionSummarizer;
    private final AgentDefinitionRepository agentDefinitionRepository;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public Flux<AgentEvent> chatStream(AiChatRequest request, Long userId, String sourceIp) {
        quotaService.checkAndIncrement(userId, "chat");

        String filteredMessage = sensitiveDataFilter.filter(request.getMessage());

        final String sid;
        AiChatSession session;

        if (request.getSessionId() == null || request.getSessionId().isBlank()) {
            session = createSession(userId, filteredMessage, request.getProvider());
            sid = session.getId();
            triggerPreviousSessionSummarization(userId, sid);
        } else {
            sid = request.getSessionId();
            session = sessionRepository.findById(sid)
                    .orElseThrow(() -> new BusinessException(404, "会话不存在"));
            if (!session.getUserId().equals(userId)) {
                throw new BusinessException(403, "无权访问此会话");
            }
        }

        saveMessage(sid, "user", filteredMessage, null, null);

        String providerKey = request.getProvider() != null ? request.getProvider() : session.getProvider();
        boolean enableTools = request.getEnableTools() == null || request.getEnableTools();

        List<AiChatMessage> history = messageRepository.findBySessionIdOrderByCreatedAtAsc(sid);
        List<Message> messages = buildMessages(history, providerKey);

        OrchestratorRequest orchRequest = new OrchestratorRequest();
        orchRequest.setSessionId(sid);
        orchRequest.setUserMessage(filteredMessage);
        orchRequest.setUserId(userId);
        orchRequest.setSourceIp(sourceIp);
        orchRequest.setProvider(providerKey);
        orchRequest.setModel(request.getModel());
        orchRequest.setEnableTools(enableTools);
        orchRequest.setTargetAgentIds(request.getTargetAgentIds());
        orchRequest.setHistory(messages);

        // Read orchestrator AgentDefinition to apply its maxIterations and model config
        agentDefinitionRepository.findByNameAndEnabledTrue("orchestrator").ifPresent(orch -> {
            if (orch.getMaxIterations() != null && orch.getMaxIterations() > 0) {
                orchRequest.setMaxIterations(orch.getMaxIterations());
            }
            // Use orchestrator's model config as fallback when request doesn't specify
            if (orchRequest.getProvider() == null || orchRequest.getProvider().isBlank() || "default".equals(orchRequest.getProvider())) {
                if (orch.getModelProvider() != null && !orch.getModelProvider().isBlank()) {
                    orchRequest.setProvider(orch.getModelProvider());
                    orchRequest.setModel(orch.getModelName());
                }
            }
        });

        if (Boolean.TRUE.equals(request.getSkipPlanning())) orchRequest.setSkipPlanning(true);
        if (Boolean.TRUE.equals(request.getPlanConfirmed())) orchRequest.setPlanConfirmed(true);

        return orchestratorEngine.process(orchRequest)
                .doFinally(signalType -> {
                    // Use doFinally to save message regardless of how stream terminates
                    // (completion, error, or client cancel)
                    String responseContent = orchRequest.getResponseContent();
                    if (responseContent != null && !responseContent.isEmpty()) {
                        log.debug("Saving assistant message for session {} (signal: {})", sid, signalType);
                        saveMessage(sid, "assistant", responseContent, null, null);
                        updateSessionMessageCount(sid);
                    } else {
                        log.debug("No response content to save for session {} (signal: {})", sid, signalType);
                    }
                    String auditStatus = signalType == reactor.core.publisher.SignalType.ON_COMPLETE ? "success" : "cancelled";
                    auditLogService.log(userId, "USER",
                            "AI_CHAT", "ai_chat_session", sid,
                            "AI 对话: " + (filteredMessage.length() > 50 ? filteredMessage.substring(0, 50) + "..." : filteredMessage),
                            sourceIp, auditStatus);
                })
                .doOnError(error -> {
                    log.error("Stream error for session {}: {}", sid, error.getMessage());
                })
                .onErrorResume(error -> {
                    // Emit a final error event and complete gracefully to prevent Servlet error dispatch
                    AgentEvent errorEvent = AgentEvent.error(error.getMessage());
                    return Flux.just(errorEvent);
                });
    }

    public AiChatResponseVO chat(AiChatRequest request, Long userId, String sourceIp) {
        quotaService.checkAndIncrement(userId, "chat");

        scriptExecuteTool.setContext(userId, sourceIp);
        softwareDetectTool.setContext(userId, sourceIp);

        final String sid;
        AiChatSession session;

        if (request.getSessionId() == null || request.getSessionId().isBlank()) {
            session = createSession(userId, request.getMessage(), request.getProvider());
            sid = session.getId();
        } else {
            sid = request.getSessionId();
            session = sessionRepository.findById(sid)
                    .orElseThrow(() -> new BusinessException(404, "会话不存在"));
            if (!session.getUserId().equals(userId)) {
                throw new BusinessException(403, "无权访问此会话");
            }
        }

        String filteredMessage = sensitiveDataFilter.filter(request.getMessage());
        saveMessage(sid, "user", filteredMessage, null, null);

        String providerKey = request.getProvider() != null ? request.getProvider() : session.getProvider();
        ChatModel chatModel = chatModelFactory.getChatModel(providerKey, request.getModel());

        List<AiChatMessage> history = messageRepository.findBySessionIdOrderByCreatedAtAsc(sid);
        List<Message> messages = buildMessages(history, providerKey);

        if (request.getTargetAgentIds() != null && !request.getTargetAgentIds().isEmpty()) {
            List<Agent> targetAgents = agentRepository.findAllById(request.getTargetAgentIds());
            if (!targetAgents.isEmpty()) {
                StringBuilder targetContext = new StringBuilder("用户指定了以下目标主机进行操作：\n");
                for (Agent agent : targetAgents) {
                    targetContext.append(String.format("- 主机: %s, IP: %s, 状态: %s, CPU: %.1f%%, 内存: %.1f%%, 磁盘: %.1f%%\n",
                            agent.getHostname(), agent.getIp(),
                            agent.getStatus() != null && agent.getStatus() == 1 ? "在线" : "离线",
                            agent.getCpuUsage() != null ? agent.getCpuUsage() : 0.0,
                            agent.getMemUsage() != null ? agent.getMemUsage() : 0.0,
                            agent.getDiskUsage() != null ? agent.getDiskUsage() : 0.0));
                }
                targetContext.append("\n请优先在这些主机上执行操作。如果需要执行脚本，请使用这些主机的 ID。");
                messages.add(0, new SystemMessage(targetContext.toString()));
            }
        }

        boolean enableTools = request.getEnableTools() == null || request.getEnableTools();
        Object[] tools = enableTools ? getAllTools() : new Object[0];

        ChatClient.Builder clientBuilder = ChatClient.builder(chatModel)
                .defaultSystem(SystemPrompts.OPS_ASSISTANT);

        if (enableTools) {
            clientBuilder.defaultTools(tools);
        }

        ChatClient chatClient = clientBuilder.build();

        ChatResponse response = chatClient.prompt()
                .messages(messages)
                .call()
                .chatResponse();

        String responseText = response.getResult().getOutput().getText();
        if (responseText == null) responseText = "";

        saveMessage(sid, "assistant", responseText, null, null);
        updateSessionMessageCount(sid);

        auditLogService.log(userId, "USER",
                "AI_CHAT", "ai_chat_session", sid,
                "AI 对话: " + (filteredMessage.length() > 50 ? filteredMessage.substring(0, 50) + "..." : filteredMessage),
                sourceIp, "success");

        return AiChatResponseVO.builder()
                .sessionId(sid)
                .content(responseText)
                .build();
    }

    private Object[] getAllTools() {
        return new Object[]{
                hostListTool, hostTagTool, scriptExecuteTool, softwareDetectTool,
                taskManageTool, scriptManageTool, clusterManageTool,
                monitoringTool, auditQueryTool, scheduledTaskTool, subAgentTool,
                delegateTaskTool, getTaskResultTool
        };
    }

    public List<AiChatSessionVO> listSessions(Long userId) {
        return sessionRepository.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(this::toSessionVO)
                .toList();
    }

    public List<AiChatMessageVO> getSessionMessages(String sessionId, Long userId) {
        AiChatSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(404, "会话不存在"));
        if (!session.getUserId().equals(userId)) {
            throw new BusinessException(403, "无权访问此会话");
        }

        return messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                .filter(msg -> !"system".equals(msg.getRole()))
                .map(this::toMessageVO)
                .toList();
    }

    @Transactional
    public void deleteSession(String sessionId, Long userId) {
        AiChatSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(404, "会话不存在"));
        if (!session.getUserId().equals(userId)) {
            throw new BusinessException(403, "无权访问此会话");
        }

        messageRepository.deleteBySessionId(sessionId);
        sessionRepository.delete(session);
    }

    private AiChatSession createSession(Long userId, String firstMessage, String provider) {
        AiChatSession session = new AiChatSession();
        session.setId("chat_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        session.setUserId(userId);
        session.setTitle(firstMessage.length() > 50 ? firstMessage.substring(0, 50) + "..." : firstMessage);
        session.setProvider(provider != null ? provider : "default");
        session.setMessageCount(0);
        return sessionRepository.save(session);
    }

    private void saveMessage(String sessionId, String role, String content, String toolCallId, String toolName) {
        AiChatMessage msg = new AiChatMessage();
        msg.setSessionId(sessionId);
        msg.setRole(role);
        msg.setContent(content);
        msg.setToolCallId(toolCallId);
        msg.setToolName(toolName);
        messageRepository.save(msg);
    }

    @Transactional
    public void saveProcessData(String sessionId, Long userId, String processData) {
        AiChatSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(404, "会话不存在"));
        if (!session.getUserId().equals(userId)) {
            throw new BusinessException(403, "无权访问此会话");
        }

        AiChatMessage lastAssistant = findLastAssistantMessageWithRetry(sessionId);
        
        if (lastAssistant != null) {
            lastAssistant.setProcessData(processData);
            messageRepository.save(lastAssistant);
            log.debug("Saved processData for message {} in session {}", lastAssistant.getId(), sessionId);
        } else {
            log.warn("No assistant message found to save processData for session {}", sessionId);
        }
    }

    private AiChatMessage findLastAssistantMessageWithRetry(String sessionId) {
        final int MAX_RETRIES = 3;
        final int RETRY_DELAY_MS = 500;
        
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            List<AiChatMessage> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
            for (int i = messages.size() - 1; i >= 0; i--) {
                if ("assistant".equals(messages.get(i).getRole())) {
                    return messages.get(i);
                }
            }
            if (attempt < MAX_RETRIES - 1) {
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return null;
    }

    private void updateSessionMessageCount(String sessionId) {
        sessionRepository.findById(sessionId).ifPresent(session -> {
            long count = messageRepository.countBySessionId(sessionId);
            session.setMessageCount((int) count);
            sessionRepository.save(session);
        });
    }

    private List<Message> buildMessages(List<AiChatMessage> history, String provider) {
        int contextWindow = chatModelFactory.getContextWindowSize(provider);
        double reserveRatio = agenticConfigService.getDouble("ai.context.response-reserve-ratio", 0.3);
        int tokenBudget = (int) (contextWindow * (1.0 - reserveRatio));

        int systemTokens = tokenEstimator.estimate(SystemPrompts.OPS_ASSISTANT);
        int remainingBudget = tokenBudget - systemTokens;

        List<Message> allMessages = new ArrayList<>();
        for (AiChatMessage msg : history) {
            String content = msg.getContent();
            if ("tool".equals(msg.getRole()) || msg.getToolCallId() != null) {
                content = truncateToolResult(content);
            }
            switch (msg.getRole()) {
                case "user" -> allMessages.add(new UserMessage(content));
                case "assistant" -> allMessages.add(new AssistantMessage(content));
                case "system" -> allMessages.add(new SystemMessage(content));
            }
        }

        if (allMessages.isEmpty()) return allMessages;

        int totalTokens = allMessages.stream()
                .mapToInt(tokenEstimator::estimateMessage)
                .sum();

        if (totalTokens <= remainingBudget) {
            return allMessages;
        }

        LinkedList<Message> result = new LinkedList<>();
        int usedTokens = 0;

        Message lastMessage = allMessages.get(allMessages.size() - 1);
        int lastTokens = tokenEstimator.estimateMessage(lastMessage);
        result.addFirst(lastMessage);
        usedTokens += lastTokens;

        for (int i = allMessages.size() - 2; i >= 0; i--) {
            Message msg = allMessages.get(i);
            int msgTokens = tokenEstimator.estimateMessage(msg);
            if (usedTokens + msgTokens > remainingBudget) break;
            result.addFirst(msg);
            usedTokens += msgTokens;
        }

        log.info("Context window: kept {}/{} messages, ~{} tokens (window: {})",
                result.size(), allMessages.size(), usedTokens + systemTokens, contextWindow);

        return new ArrayList<>(result);
    }

    private String truncateToolResult(String content) {
        if (content == null) return "";
        int maxLength = agenticConfigService.getInt("ai.context.tool-result-max-length", 3000);
        if (content.length() <= maxLength) return content;

        int headLength = (int) (maxLength * 0.6);
        int tailLength = (int) (maxLength * 0.3);
        return content.substring(0, headLength)
                + "\n...[truncated]...\n"
                + content.substring(content.length() - tailLength);
    }

    private AiChatSessionVO toSessionVO(AiChatSession session) {
        return AiChatSessionVO.builder()
                .id(session.getId())
                .title(session.getTitle())
                .provider(session.getProvider())
                .messageCount(session.getMessageCount())
                .createdAt(session.getCreatedAt() != null ? session.getCreatedAt().format(FMT) : null)
                .updatedAt(session.getUpdatedAt() != null ? session.getUpdatedAt().format(FMT) : null)
                .build();
    }

    private AiChatMessageVO toMessageVO(AiChatMessage msg) {
        return AiChatMessageVO.builder()
                .id(msg.getId())
                .sessionId(msg.getSessionId())
                .role(msg.getRole())
                .content(msg.getContent())
                .toolName(msg.getToolName())
                .processData(msg.getProcessData())
                .createdAt(msg.getCreatedAt() != null ? msg.getCreatedAt().format(FMT) : null)
                .build();
    }

    private void triggerPreviousSessionSummarization(Long userId, String currentSessionId) {
        try {
            List<AiChatSession> userSessions = sessionRepository.findByUserIdOrderByUpdatedAtDesc(userId);
            for (AiChatSession s : userSessions) {
                if (s.getId().equals(currentSessionId)) continue;
                if (Boolean.TRUE.equals(s.getSummaryGenerated())) continue;
                if (s.getMessageCount() != null && s.getMessageCount() >= 3) {
                    sessionSummarizer.summarizeSession(s.getId(), userId);
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to trigger session summarization for user {}: {}", userId, e.getMessage());
        }
    }

    public void triggerSessionSummarization(String sessionId, Long userId) {
        sessionSummarizer.summarizeSession(sessionId, userId);
    }
}
