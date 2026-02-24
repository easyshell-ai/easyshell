package com.easyshell.server.ai.learning;

import com.easyshell.server.ai.config.AgenticConfigService;
import com.easyshell.server.ai.model.entity.AiSopTemplate;
import com.easyshell.server.ai.orchestrator.ExecutionPlan;
import com.easyshell.server.ai.repository.AiSopTemplateRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class SopRetriever {

    private final AiSopTemplateRepository sopTemplateRepository;
    private final AgenticConfigService configService;

    @Autowired(required = false)
    @Lazy
    private VectorStore vectorStore;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public Optional<AiSopTemplate> findMatchingSop(String userMessage, Long userId) {
        try {
            if (!configService.getBoolean("ai.sop.enabled", true)) return Optional.empty();
            if (userMessage == null || userMessage.isBlank()) return Optional.empty();
            if (vectorStore == null) {
                log.debug("VectorStore not available, skipping SOP retrieval");
                return Optional.empty();
            }

            double threshold = configService.getDouble("ai.sop.confidence-threshold", 0.7);
            int minSuccess = configService.getInt("ai.sop.min-success-count", 3);

            SearchRequest searchRequest = SearchRequest.builder()
                    .query(userMessage)
                    .topK(5)
                    .similarityThreshold(0.75)
                    .build();

            List<Document> docs = vectorStore.similaritySearch(searchRequest);
            if (docs == null || docs.isEmpty()) return Optional.empty();

            for (Document doc : docs) {
                Map<String, Object> metadata = doc.getMetadata();
                if (metadata == null) continue;

                Object sopIdObj = metadata.get("sopId");
                if (sopIdObj == null) continue;

                Long sopId;
                try {
                    sopId = Long.parseLong(sopIdObj.toString());
                } catch (NumberFormatException e) {
                    continue;
                }

                Optional<AiSopTemplate> sopOpt = sopTemplateRepository.findById(sopId);
                if (sopOpt.isEmpty()) continue;

                AiSopTemplate sop = sopOpt.get();
                if (!sop.getEnabled()) continue;
                if (sop.getConfidence() < threshold) continue;
                if (sop.getSuccessCount() < minSuccess) continue;

                if (sop.getUserId() != null && !sop.getUserId().equals(userId)) continue;

                log.info("Found matching SOP '{}' (id={}, confidence={}) for user message",
                        sop.getTitle(), sop.getId(), sop.getConfidence());
                return Optional.of(sop);
            }

            return Optional.empty();
        } catch (Exception e) {
            log.warn("SOP retrieval failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public ExecutionPlan sopToPlan(AiSopTemplate sop) {
        try {
            JsonNode root = MAPPER.readTree(sop.getStepsJson());
            JsonNode stepsNode = root.get("steps");
            if (stepsNode == null || !stepsNode.isArray() || stepsNode.isEmpty()) return null;

            List<ExecutionPlan.PlanStep> planSteps = new ArrayList<>();
            for (JsonNode stepNode : stepsNode) {
                int index = stepNode.has("index") ? stepNode.get("index").asInt() : planSteps.size();
                String description = stepNode.has("description") ? stepNode.get("description").asText() : "";
                String agent = stepNode.has("agent") ? stepNode.get("agent").asText() : "execute";

                List<String> tools = new ArrayList<>();
                if (stepNode.has("tools") && stepNode.get("tools").isArray()) {
                    for (JsonNode t : stepNode.get("tools")) {
                        tools.add(t.asText());
                    }
                }

                planSteps.add(ExecutionPlan.PlanStep.builder()
                        .index(index)
                        .description(description)
                        .agent(agent)
                        .tools(tools)
                        .status("pending")
                        .build());
            }

            String risk = root.has("estimated_risk") ? root.get("estimated_risk").asText() : "LOW";

            return ExecutionPlan.builder()
                    .summary("[SOP] " + sop.getTitle())
                    .steps(planSteps)
                    .requiresConfirmation(true)
                    .estimatedRisk(risk)
                    .build();
        } catch (Exception e) {
            log.error("Failed to convert SOP '{}' to plan: {}", sop.getTitle(), e.getMessage());
            return null;
        }
    }
}
