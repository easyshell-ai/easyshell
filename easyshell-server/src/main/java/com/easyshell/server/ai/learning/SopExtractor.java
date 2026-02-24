package com.easyshell.server.ai.learning;

import com.easyshell.server.ai.config.AgenticConfigService;
import com.easyshell.server.ai.model.entity.AiIterationMessage;
import com.easyshell.server.ai.model.entity.AiSessionSummary;
import com.easyshell.server.ai.model.entity.AiSopTemplate;
import com.easyshell.server.ai.repository.AiIterationMessageRepository;
import com.easyshell.server.ai.repository.AiSessionSummaryRepository;
import com.easyshell.server.ai.repository.AiSopTemplateRepository;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class SopExtractor {

    private final AiIterationMessageRepository iterationMessageRepository;
    private final AiSessionSummaryRepository sessionSummaryRepository;
    private final AiSopTemplateRepository sopTemplateRepository;
    private final ChatModelFactory chatModelFactory;
    private final AgenticConfigService configService;

    @Autowired(required = false)
    @Lazy
    private VectorStore vectorStore;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern JSON_BLOCK_PATTERN =
            Pattern.compile("```(?:json)?\\s*\\n?(.*?)\\n?\\s*```", Pattern.DOTALL);

    private static final String EXTRACT_SOP_PROMPT = """
            Analyze the following AI operations session trace and determine if it contains a repeatable SOP (Standard Operating Procedure).
            
            A valid SOP must:
            1. Have clear sequential steps that could be reused for similar requests
            2. Involve tool calls that succeeded
            3. Be generalizable (not specific to one-time unique situations)
            
            If a valid SOP is found, return a JSON object:
            {
              "found": true,
              "title": "Short descriptive title",
              "description": "What this SOP accomplishes",
              "category": "deployment|troubleshooting|monitoring|maintenance|query|other",
              "trigger_pattern": "Natural language pattern that would trigger this SOP",
              "steps": [
                {"index": 1, "description": "Step description", "agent": "execute", "tools": ["toolName"]}
              ],
              "estimated_risk": "LOW|MEDIUM|HIGH"
            }
            
            If no valid SOP is found, return:
            {"found": false}
            
            Return ONLY JSON, no other text.
            """;

    @Async
    public void tryExtractFromSession(String sessionId) {
        try {
            if (!configService.getBoolean("ai.sop.enabled", true)) return;

            Optional<AiSessionSummary> summaryOpt = sessionSummaryRepository.findBySessionId(sessionId);
            if (summaryOpt.isEmpty()) return;

            AiSessionSummary summary = summaryOpt.get();
            if (!"SUCCESS".equals(summary.getOutcome())) return;

            List<AiIterationMessage> iterations = iterationMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
            if (iterations.size() < 2) return;

            boolean allToolsSucceeded = iterations.stream()
                    .filter(m -> "tool_result".equals(m.getRole()))
                    .noneMatch(m -> m.getContent() != null && m.getContent().startsWith("[Error"));

            if (!allToolsSucceeded) return;

            StringBuilder trace = new StringBuilder();
            trace.append("Session Summary: ").append(summary.getSummary()).append("\n\n");
            for (AiIterationMessage msg : iterations) {
                trace.append("[").append(msg.getRole().toUpperCase()).append("]");
                if (msg.getToolName() != null) trace.append(" tool=").append(msg.getToolName());
                if (msg.getContent() != null) {
                    String content = msg.getContent().length() > 500
                            ? msg.getContent().substring(0, 500) + "..."
                            : msg.getContent();
                    trace.append(" ").append(content);
                }
                trace.append("\n");
            }

            int maxChars = configService.getInt("ai.sop.extraction-input-max-chars", 6000);
            String input = trace.toString();
            if (input.length() > maxChars) {
                input = input.substring(0, maxChars) + "\n...[truncated]";
            }

            ChatModel chatModel = chatModelFactory.getChatModel(null);
            List<org.springframework.ai.chat.messages.Message> promptMessages = List.of(
                    new SystemMessage(EXTRACT_SOP_PROMPT),
                    new UserMessage("Session trace:\n\n" + input)
            );
            ChatResponse response = chatModel.call(new Prompt(promptMessages));
            String responseText = response.getResult().getOutput().getText();

            if (responseText == null || responseText.isBlank()) return;

            String json = extractJson(responseText);
            JsonNode node = MAPPER.readTree(json);

            if (!node.has("found") || !node.get("found").asBoolean()) return;

            String title = node.has("title") ? node.get("title").asText() : null;
            if (title == null || title.isBlank()) return;

            Optional<AiSopTemplate> existing = sopTemplateRepository.findByTitle(title);
            if (existing.isPresent()) {
                AiSopTemplate existingSop = existing.get();
                existingSop.setTotalCount(existingSop.getTotalCount() + 1);
                existingSop.setSuccessCount(existingSop.getSuccessCount() + 1);
                existingSop.setConfidence(
                        (double) existingSop.getSuccessCount() / existingSop.getTotalCount());
                String ids = existingSop.getSourceSessionIds();
                existingSop.setSourceSessionIds(
                        ids != null ? ids + "," + sessionId : sessionId);
                sopTemplateRepository.save(existingSop);
                log.info("Updated existing SOP '{}' with session {}", title, sessionId);
                return;
            }

            JsonNode stepsNode = node.get("steps");
            if (stepsNode == null || !stepsNode.isArray() || stepsNode.isEmpty()) return;

            Map<String, Object> stepsMap = new LinkedHashMap<>();
            stepsMap.put("steps", MAPPER.treeToValue(stepsNode, List.class));
            if (node.has("estimated_risk")) {
                stepsMap.put("estimated_risk", node.get("estimated_risk").asText());
            }

            AiSopTemplate sop = AiSopTemplate.builder()
                    .title(title)
                    .description(node.has("description") ? node.get("description").asText() : null)
                    .stepsJson(MAPPER.writeValueAsString(stepsMap))
                    .triggerPattern(node.has("trigger_pattern") ? node.get("trigger_pattern").asText() : null)
                    .category(node.has("category") ? node.get("category").asText() : "other")
                    .successCount(1)
                    .totalCount(1)
                    .confidence(1.0)
                    .sourceSessionIds(sessionId)
                    .userId(summary.getUserId())
                    .enabled(true)
                    .build();

            String embeddingId = UUID.randomUUID().toString();
            sop.setEmbeddingId(embeddingId);

            String embeddingText = buildEmbeddingText(sop);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("sopId", "pending");
            metadata.put("category", sop.getCategory());
            if (sop.getTriggerPattern() != null) {
                metadata.put("triggerPattern", sop.getTriggerPattern());
            }

            sopTemplateRepository.save(sop);

            metadata.put("sopId", sop.getId().toString());
            Document doc = new Document(embeddingId, embeddingText, metadata);
            if (vectorStore != null) {
                vectorStore.add(List.of(doc));
            } else {
                log.warn("VectorStore not available, skipping embedding for SOP '{}'", title);
            }

            log.info("Extracted new SOP '{}' (id={}) from session {}", title, sop.getId(), sessionId);

        } catch (Exception e) {
            log.error("Failed to extract SOP from session {}: {}", sessionId, e.getMessage(), e);
        }
    }

    @Scheduled(cron = "#{@agenticConfigService.get('ai.sop.extraction-cron', '0 0 3 * * ?')}")
    public void extractSopPatterns() {
        if (!configService.getBoolean("ai.sop.enabled", true)) return;

        log.info("Starting scheduled SOP extraction");
        List<AiSessionSummary> summaries = sessionSummaryRepository.findAll();

        int extracted = 0;
        for (AiSessionSummary summary : summaries) {
            if (!"SUCCESS".equals(summary.getOutcome())) continue;
            try {
                tryExtractFromSession(summary.getSessionId());
                extracted++;
            } catch (Exception e) {
                log.warn("Scheduled SOP extraction failed for session {}: {}",
                        summary.getSessionId(), e.getMessage());
            }
        }
        log.info("Scheduled SOP extraction completed: processed {} sessions", extracted);
    }

    private String extractJson(String text) {
        Matcher matcher = JSON_BLOCK_PATTERN.matcher(text);
        if (matcher.find()) return matcher.group(1).trim();
        String trimmed = text.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed;
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) return trimmed.substring(start, end + 1);
        return trimmed;
    }

    private String buildEmbeddingText(AiSopTemplate sop) {
        StringBuilder sb = new StringBuilder();
        if (sop.getTitle() != null) sb.append(sop.getTitle()).append(". ");
        if (sop.getDescription() != null) sb.append(sop.getDescription()).append(" ");
        if (sop.getTriggerPattern() != null) sb.append("Trigger: ").append(sop.getTriggerPattern()).append(" ");
        if (sop.getCategory() != null) sb.append("Category: ").append(sop.getCategory());
        return sb.toString().trim();
    }
}
