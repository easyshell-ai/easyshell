package com.easyshell.server.ai.memory;

import com.easyshell.server.ai.config.AgenticConfigService;
import com.easyshell.server.ai.model.entity.AiSessionSummary;
import com.easyshell.server.ai.repository.AiSessionSummaryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MemoryRetriever — long-term memory retrieval")
class MemoryRetrieverTest {

    @Mock
    private VectorStore vectorStore;
    @Mock
    private AiSessionSummaryRepository summaryRepository;
    @Mock
    private AgenticConfigService configService;

    private MemoryRetriever retriever;

    @BeforeEach
    void setUp() {
        retriever = new MemoryRetriever(vectorStore, summaryRepository, configService);
    }

    private void enableMemory() {
        when(configService.getBoolean(eq("ai.memory.enabled"), anyBoolean())).thenReturn(true);
        lenient().when(configService.getInt(eq("ai.memory.retrieval-top-k"), anyInt())).thenReturn(5);
        lenient().when(configService.getDouble(eq("ai.memory.similarity-threshold"), anyDouble())).thenReturn(0.7);
        lenient().when(configService.getDouble(eq("ai.memory.token-budget-ratio"), anyDouble())).thenReturn(0.15);
    }

    @Test
    void disabledMemory_returnsNull() {
        when(configService.getBoolean(eq("ai.memory.enabled"), anyBoolean())).thenReturn(false);
        assertThat(retriever.retrieveRelevantMemory(1L, "test")).isNull();
    }

    @Test
    void nullMessage_returnsNull() {
        when(configService.getBoolean(eq("ai.memory.enabled"), anyBoolean())).thenReturn(true);
        assertThat(retriever.retrieveRelevantMemory(1L, null)).isNull();
    }

    @Test
    void blankMessage_returnsNull() {
        when(configService.getBoolean(eq("ai.memory.enabled"), anyBoolean())).thenReturn(true);
        assertThat(retriever.retrieveRelevantMemory(1L, "   ")).isNull();
    }

    @Test
    void noResults_returnsNull() {
        enableMemory();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        assertThat(retriever.retrieveRelevantMemory(1L, "restart nginx")).isNull();
    }

    @Test
    void resultsFilteredByUserId() {
        enableMemory();

        Document matchDoc = new Document("emb-1", "session about nginx", Map.of("userId", "1"));
        Document otherUserDoc = new Document("emb-2", "session about mysql", Map.of("userId", "2"));

        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(matchDoc, otherUserDoc));
        when(summaryRepository.findAll()).thenReturn(List.of());

        String result = retriever.retrieveRelevantMemory(1L, "nginx issue");
        assertThat(result).isNotNull();
        assertThat(result).contains("nginx");
        assertThat(result).doesNotContain("mysql");
    }
}
