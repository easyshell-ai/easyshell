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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SessionSummarizer — session summary generation")
class SessionSummarizerTest {

    @Mock private AiChatMessageRepository messageRepository;
    @Mock private AiSessionSummaryRepository summaryRepository;
    @Mock private AiChatSessionRepository sessionRepository;
    @Mock private ChatModelFactory chatModelFactory;
    @Mock private AgenticConfigService configService;
    @Mock private VectorStore vectorStore;
    @Mock private SopExtractor sopExtractor;

    private SessionSummarizer summarizer;

    @BeforeEach
    void setUp() {
        summarizer = new SessionSummarizer(
                messageRepository, summaryRepository, sessionRepository,
                chatModelFactory, configService, vectorStore, sopExtractor);
    }

    @Test
    void disabledMemory_skips() {
        when(configService.getBoolean(eq("ai.memory.enabled"), anyBoolean())).thenReturn(false);
        summarizer.summarizeSession("session-1", 1L);
        verify(messageRepository, never()).findBySessionIdOrderByCreatedAtAsc(any());
    }

    @Test
    void alreadySummarized_skips() {
        when(configService.getBoolean(eq("ai.memory.enabled"), anyBoolean())).thenReturn(true);
        when(summaryRepository.existsBySessionId("session-1")).thenReturn(true);
        summarizer.summarizeSession("session-1", 1L);
        verify(messageRepository, never()).findBySessionIdOrderByCreatedAtAsc(any());
    }

    @Test
    void tooFewMessages_skips() {
        when(configService.getBoolean(eq("ai.memory.enabled"), anyBoolean())).thenReturn(true);
        when(summaryRepository.existsBySessionId("session-1")).thenReturn(false);
        when(configService.getInt(eq("ai.memory.min-messages-for-summary"), anyInt())).thenReturn(3);

        AiChatMessage msg1 = new AiChatMessage();
        msg1.setRole("user");
        msg1.setContent("hello");
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc("session-1")).thenReturn(List.of(msg1));

        summarizer.summarizeSession("session-1", 1L);
        verify(chatModelFactory, never()).getChatModel(any());
    }

    @Test
    void exceptionInSummarization_doesNotPropagate() {
        when(configService.getBoolean(eq("ai.memory.enabled"), anyBoolean())).thenReturn(true);
        when(summaryRepository.existsBySessionId("session-1")).thenReturn(false);
        when(configService.getInt(eq("ai.memory.min-messages-for-summary"), anyInt())).thenReturn(1);

        AiChatMessage msg1 = new AiChatMessage();
        msg1.setRole("user");
        msg1.setContent("test message");
        AiChatMessage msg2 = new AiChatMessage();
        msg2.setRole("assistant");
        msg2.setContent("response");
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc("session-1")).thenReturn(List.of(msg1, msg2));

        when(configService.getInt(eq("ai.memory.summary-input-max-chars"), anyInt())).thenReturn(8000);
        when(chatModelFactory.getChatModel(any())).thenThrow(new RuntimeException("LLM unavailable"));

        summarizer.summarizeSession("session-1", 1L);
        verify(summaryRepository, never()).save(any(AiSessionSummary.class));
    }
}
