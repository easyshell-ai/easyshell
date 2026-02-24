package com.easyshell.server.ai.memory;

import com.easyshell.server.ai.config.AgenticConfigService;
import com.easyshell.server.ai.model.entity.AiSessionSummary;
import com.easyshell.server.ai.repository.AiSessionSummaryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryRetriever {

    private final AiSessionSummaryRepository summaryRepository;
    private final AgenticConfigService configService;

    @Autowired(required = false)
    @Lazy
    private VectorStore vectorStore;

    public String retrieveRelevantMemory(Long userId, String userMessage) {
        if (!configService.getBoolean("ai.memory.enabled", true)) {
            return null;
        }

        if (userMessage == null || userMessage.isBlank()) {
            return null;
        }

        if (vectorStore == null) {
            log.debug("VectorStore not available, skipping memory retrieval");
            return null;
        }

        try {
            int topK = configService.getInt("ai.memory.retrieval-top-k", 5);
            double threshold = configService.getDouble("ai.memory.similarity-threshold", 0.7);

            SearchRequest searchRequest = SearchRequest.builder()
                    .query(userMessage)
                    .topK(topK)
                    .similarityThreshold(threshold)
                    .build();

            List<Document> results = vectorStore.similaritySearch(searchRequest);

            if (results == null || results.isEmpty()) {
                log.debug("No relevant memories found for user {}", userId);
                return null;
            }

            List<Document> userResults = results.stream()
                    .filter(doc -> {
                        Object docUserId = doc.getMetadata().get("userId");
                        return docUserId != null && docUserId.toString().equals(userId.toString());
                    })
                    .collect(Collectors.toList());

            if (userResults.isEmpty()) {
                log.debug("No memories matching user {} after filtering", userId);
                return null;
            }

            double tokenBudgetRatio = configService.getDouble("ai.memory.token-budget-ratio", 0.15);
            int maxChars = (int) (4000 * tokenBudgetRatio * 4);

            StringBuilder memoryContext = new StringBuilder();
            int charCount = 0;

            for (Document doc : userResults) {
                String embeddingId = doc.getId();
                Optional<AiSessionSummary> summaryOpt = findSummaryByEmbeddingId(embeddingId);

                String entry;
                if (summaryOpt.isPresent()) {
                    AiSessionSummary s = summaryOpt.get();
                    entry = formatSummaryEntry(s);
                } else {
                    entry = "- " + truncate(doc.getText(), 200) + "\n";
                }

                if (charCount + entry.length() > maxChars) {
                    break;
                }

                memoryContext.append(entry);
                charCount += entry.length();
            }

            if (memoryContext.isEmpty()) {
                return null;
            }

            log.info("Retrieved {} memory entries for user {} ({} chars)",
                    userResults.size(), userId, charCount);

            return memoryContext.toString().trim();

        } catch (Exception e) {
            log.warn("Memory retrieval failed for user {}: {}", userId, e.getMessage());
            return null;
        }
    }

    private Optional<AiSessionSummary> findSummaryByEmbeddingId(String embeddingId) {
        List<AiSessionSummary> all = summaryRepository.findAll();
        return all.stream()
                .filter(s -> embeddingId.equals(s.getEmbeddingId()))
                .findFirst();
    }

    private String formatSummaryEntry(AiSessionSummary s) {
        StringBuilder sb = new StringBuilder();
        sb.append("- **Session**: ").append(s.getSessionId());
        if (s.getOutcome() != null) {
            sb.append(" [").append(s.getOutcome()).append("]");
        }
        sb.append("\n");
        if (s.getSummary() != null) {
            sb.append("  ").append(truncate(s.getSummary(), 300)).append("\n");
        }
        if (s.getHostsInvolved() != null) {
            sb.append("  Hosts: ").append(s.getHostsInvolved()).append("\n");
        }
        if (s.getServicesInvolved() != null) {
            sb.append("  Services: ").append(s.getServicesInvolved()).append("\n");
        }
        return sb.toString();
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
