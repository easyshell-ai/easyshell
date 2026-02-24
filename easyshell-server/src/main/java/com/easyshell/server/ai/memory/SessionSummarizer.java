package com.easyshell.server.ai.memory;

import com.easyshell.server.ai.config.AgenticConfigService;
import com.easyshell.server.ai.learning.SopExtractor;
import com.easyshell.server.ai.model.entity.AiChatMessage;
import com.easyshell.server.ai.model.entity.AiChatSession;
import com.easyshell.server.ai.model.entity.AiSessionSummary;
import com.easyshell.server.ai.repository.AiChatMessageRepository;
import com.easyshell.server.ai.repository.AiChatSessionRepository;
import com.easyshell.server.ai.repository.AiSessionSummaryRepository;
import com.easyshell.server.ai.service.ChatModelFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionSummarizer {

    private final AiChatMessageRepository messageRepository;
    private final AiSessionSummaryRepository summaryRepository;
    private final AiChatSessionRepository sessionRepository;
    private final ChatModelFactory chatModelFactory;
    private final AgenticConfigService configService;
    private final SopExtractor sopExtractor;

    @Autowired(required = false)
    @Lazy
    private VectorStore vectorStore;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern JSON_BLOCK_PATTERN =
            Pattern.compile("```(?:json)?\\s*\\n?(.*?)\\n?\\s*```", Pattern.DOTALL);

    private static final String SUMMARIZE_PROMPT = """
            Analyze the following AI chat session and produce a structured JSON summary.
            Focus on WHAT was done, WHERE (which hosts/services), and the OUTCOME.
            
            Return ONLY a JSON object with these fields:
            {
              "summary": "2-3 sentence natural language summary of the session",
              "key_operations": ["operation1", "operation2"],
              "hosts_involved": ["hostname1", "hostname2"],
              "services_involved": ["nginx", "mysql"],
              "outcome": "SUCCESS|PARTIAL|FAILED",
              "tags": ["deployment", "troubleshooting", "monitoring"]
            }
            
            If a field has no data, use an empty array [] or "UNKNOWN" for outcome.
            Return ONLY the JSON, no other text.
            """;

    @Async
    public void summarizeSession(String sessionId, Long userId) {
        try {
            if (!configService.getBoolean("ai.memory.enabled", true)) {
                return;
            }

            if (summaryRepository.existsBySessionId(sessionId)) {
                log.debug("Session {} already summarized, skipping", sessionId);
                return;
            }

            List<AiChatMessage> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
            int minMessages = configService.getInt("ai.memory.min-messages-for-summary", 3);
            if (messages.size() < minMessages) {
                log.debug("Session {} has only {} messages, skipping summary (min: {})",
                        sessionId, messages.size(), minMessages);
                return;
            }

            StringBuilder conversationText = new StringBuilder();
            for (AiChatMessage msg : messages) {
                if ("system".equals(msg.getRole())) continue;
                conversationText.append("[").append(msg.getRole().toUpperCase()).append("] ")
                        .append(msg.getContent() != null ? msg.getContent() : "")
                        .append("\n\n");
            }

            int maxChars = configService.getInt("ai.memory.summary-input-max-chars", 8000);
            String input = conversationText.toString();
            if (input.length() > maxChars) {
                input = input.substring(0, maxChars) + "\n...[truncated]";
            }

            ChatModel chatModel = chatModelFactory.getChatModel(null);
            List<org.springframework.ai.chat.messages.Message> promptMessages = List.of(
                    new SystemMessage(SUMMARIZE_PROMPT),
                    new UserMessage("Session conversation:\n\n" + input)
            );
            ChatResponse response = chatModel.call(new Prompt(promptMessages));
            String responseText = response.getResult().getOutput().getText();

            if (responseText == null || responseText.isBlank()) {
                log.warn("Empty summary response for session {}", sessionId);
                return;
            }

            String json = extractJson(responseText);
            JsonNode node = MAPPER.readTree(json);

            AiSessionSummary summary = new AiSessionSummary();
            summary.setSessionId(sessionId);
            summary.setUserId(userId);
            summary.setSummary(getTextOrNull(node, "summary"));
            summary.setKeyOperations(getArrayAsString(node, "key_operations"));
            summary.setHostsInvolved(getArrayAsCsv(node, "hosts_involved"));
            summary.setServicesInvolved(getArrayAsCsv(node, "services_involved"));
            summary.setOutcome(getTextOrDefault(node, "outcome", "UNKNOWN"));
            summary.setTags(getArrayAsCsv(node, "tags"));

            String embeddingId = UUID.randomUUID().toString();
            summary.setEmbeddingId(embeddingId);

            String embeddingText = buildEmbeddingText(summary);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("sessionId", sessionId);
            metadata.put("userId", userId.toString());
            metadata.put("outcome", summary.getOutcome());
            if (summary.getTags() != null) {
                metadata.put("tags", summary.getTags());
            }

            Document doc = new Document(embeddingId, embeddingText, metadata);
            if (vectorStore != null) {
                vectorStore.add(List.of(doc));
            } else {
                log.warn("VectorStore not available, skipping embedding for session {}", sessionId);
            }

            summaryRepository.save(summary);

            sessionRepository.findById(sessionId).ifPresent(session -> {
                session.setSummaryGenerated(true);
                sessionRepository.save(session);
            });

            log.info("Generated summary for session {} (embedding: {})", sessionId, embeddingId);

            try {
                sopExtractor.tryExtractFromSession(sessionId);
            } catch (Exception sopEx) {
                log.warn("SOP extraction failed for session {}: {}", sessionId, sopEx.getMessage());
            }

        } catch (Exception e) {
            log.error("Failed to summarize session {}: {}", sessionId, e.getMessage(), e);
        }
    }

    private String extractJson(String text) {
        Matcher matcher = JSON_BLOCK_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    private String buildEmbeddingText(AiSessionSummary summary) {
        StringBuilder sb = new StringBuilder();
        if (summary.getSummary() != null) {
            sb.append(summary.getSummary()).append(" ");
        }
        if (summary.getKeyOperations() != null) {
            sb.append("Operations: ").append(summary.getKeyOperations()).append(" ");
        }
        if (summary.getHostsInvolved() != null) {
            sb.append("Hosts: ").append(summary.getHostsInvolved()).append(" ");
        }
        if (summary.getServicesInvolved() != null) {
            sb.append("Services: ").append(summary.getServicesInvolved()).append(" ");
        }
        if (summary.getTags() != null) {
            sb.append("Tags: ").append(summary.getTags());
        }
        return sb.toString().trim();
    }

    private String getTextOrNull(JsonNode node, String field) {
        JsonNode f = node.get(field);
        return (f != null && !f.isNull() && f.isTextual()) ? f.asText() : null;
    }

    private String getTextOrDefault(JsonNode node, String field, String defaultValue) {
        String val = getTextOrNull(node, field);
        return val != null ? val : defaultValue;
    }

    private String getArrayAsString(JsonNode node, String field) {
        JsonNode arr = node.get(field);
        if (arr == null || !arr.isArray()) return null;
        return arr.toString();
    }

    private String getArrayAsCsv(JsonNode node, String field) {
        JsonNode arr = node.get(field);
        if (arr == null || !arr.isArray() || arr.isEmpty()) return null;
        List<String> items = new ArrayList<>();
        for (JsonNode item : arr) {
            items.add(item.asText());
        }
        return String.join(",", items);
    }
}
