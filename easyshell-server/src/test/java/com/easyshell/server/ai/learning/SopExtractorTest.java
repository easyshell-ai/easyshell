package com.easyshell.server.ai.learning;

import com.easyshell.server.ai.config.AgenticConfigService;
import com.easyshell.server.ai.model.entity.AiChatSession;
import com.easyshell.server.ai.model.entity.AiIterationMessage;
import com.easyshell.server.ai.model.entity.AiSessionSummary;
import com.easyshell.server.ai.model.entity.AiSopTemplate;
import com.easyshell.server.ai.repository.AiChatSessionRepository;
import com.easyshell.server.ai.repository.AiIterationMessageRepository;
import com.easyshell.server.ai.repository.AiSessionSummaryRepository;
import com.easyshell.server.ai.repository.AiSopTemplateRepository;
import com.easyshell.server.ai.service.ChatModelFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SopExtractorTest {
    @Mock AiIterationMessageRepository iterations;
    @Mock AiChatSessionRepository sessions;
    @Mock AiSessionSummaryRepository summaries;
    @Mock AiSopTemplateRepository templates;
    @Mock ChatModelFactory factory;
    @Mock AgenticConfigService config;
    @Mock ChatModel model;
    private SopExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new SopExtractor(iterations, sessions, summaries, templates, factory, config);
        when(config.getBoolean("ai.sop.enabled", true)).thenReturn(true);
    }

    private void summary(Long owner) {
        AiSessionSummary summary = new AiSessionSummary();
        summary.setOutcome("SUCCESS");
        summary.setUserId(owner);
        when(summaries.findBySessionId("s1")).thenReturn(Optional.of(summary));
    }

    private void session(Long owner) {
        AiChatSession session = new AiChatSession();
        session.setUserId(owner);
        when(sessions.findById("s1")).thenReturn(Optional.of(session));
    }

    private void modelResponse() {
        AiIterationMessage message = new AiIterationMessage();
        message.setRole("assistant");
        when(iterations.findBySessionIdOrderByCreatedAtAsc("s1")).thenReturn(List.of(message, message));
        when(config.getInt("ai.sop.extraction-input-max-chars", 6000)).thenReturn(6000);
        when(factory.getChatModel(null)).thenReturn(model);
        ChatResponse response = mock(ChatResponse.class, RETURNS_DEEP_STUBS);
        when(model.call(any(Prompt.class))).thenReturn(response);
        when(response.getResult().getOutput().getText()).thenReturn(
                "{\"found\":true,\"title\":\"Same\",\"steps\":[{\"index\":1}]}");
    }

    @ParameterizedTest
    @CsvSource(value = {"null, 1", "1, null", "0, 0", "-1, -1", "1, 2"}, nullValues = "null")
    void invalidOrInconsistentOwnerFailsClosed(Long sessionOwner, Long summaryOwner) {
        summary(summaryOwner);
        session(sessionOwner);
        extractor.tryExtractFromSession("s1");
        verifyNoInteractions(iterations, templates, factory);
    }

    @Test
    void missingSessionFailsClosed() {
        summary(1L);
        when(sessions.findById("s1")).thenReturn(Optional.empty());
        extractor.tryExtractFromSession("s1");
        verifyNoInteractions(iterations, templates, factory);
    }

    @Test
    void mergesOnlyOwnersMatchingTitle() {
        summary(1L);
        session(1L);
        modelResponse();
        AiSopTemplate own = AiSopTemplate.builder().userId(1L).title("Same")
                .successCount(2).totalCount(3).sourceSessionIds("old").build();
        when(templates.findFirstByUserIdAndTitleOrderByIdAsc(1L, "Same")).thenReturn(Optional.of(own));
        extractor.tryExtractFromSession("s1");
        assertThat(own.getSuccessCount()).isEqualTo(3);
        assertThat(own.getTotalCount()).isEqualTo(4);
        assertThat(own.getSourceSessionIds()).isEqualTo("old,s1");
        verify(templates).save(own);
    }

    @Test
    void createsPrivateTemplateWhenOnlyOtherOwnersOrSharedTitlesExist() {
        summary(1L);
        session(1L);
        modelResponse();
        when(templates.findFirstByUserIdAndTitleOrderByIdAsc(1L, "Same")).thenReturn(Optional.empty());
        when(templates.save(any(AiSopTemplate.class))).thenAnswer(invocation -> {
            AiSopTemplate sop = invocation.getArgument(0);
            sop.setId(10L);
            return sop;
        });
        extractor.tryExtractFromSession("s1");
        ArgumentCaptor<AiSopTemplate> saved = ArgumentCaptor.forClass(AiSopTemplate.class);
        verify(templates).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(1L);
        assertThat(saved.getValue().getSourceSessionIds()).isEqualTo("s1");
    }

    @ParameterizedTest
    @CsvSource(value = {"null", "2"}, nullValues = "null")
    void refusesUnexpectedSharedOrOtherOwnerResult(Long owner) {
        summary(1L);
        session(1L);
        modelResponse();
        AiSopTemplate foreign = AiSopTemplate.builder().userId(owner).title("Same")
                .successCount(2).totalCount(3).sourceSessionIds("old").build();
        when(templates.findFirstByUserIdAndTitleOrderByIdAsc(1L, "Same")).thenReturn(Optional.of(foreign));
        extractor.tryExtractFromSession("s1");
        verify(templates, never()).save(any());
        assertThat(foreign.getSourceSessionIds()).isEqualTo("old");
        assertThat(foreign.getTotalCount()).isEqualTo(3);
    }
}
