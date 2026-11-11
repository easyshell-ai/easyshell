package com.easyshell.server.ai.service;

import com.easyshell.server.ai.memory.SessionSummarizer;
import com.easyshell.server.ai.model.entity.AiChatSession;
import com.easyshell.server.ai.repository.AiChatSessionRepository;
import com.easyshell.server.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiChatServiceSummarizationTest {
    @Mock private AiChatSessionRepository sessions;
    @Mock private SessionSummarizer summarizer;
    @InjectMocks private AiChatService service;

    private static AiChatSession session(String id, Long userId, Integer count, boolean summarized) {
        AiChatSession session = new AiChatSession();
        session.setId(id);
        session.setUserId(userId);
        session.setMessageCount(count);
        session.setSummaryGenerated(summarized);
        return session;
    }

    @Test void queuesAndCountsOnlyEligibleOwnedSessions() {
        when(sessions.findByUserIdOrderByUpdatedAtDesc(1L)).thenReturn(List.of(
                session("own", 1L, 3, false), session("other", 2L, 5, false),
                session("missing-owner", null, 5, false), session("short", 1L, 2, false),
                session("no-count", 1L, null, false), session("done", 1L, 5, true)));

        assertThat(service.triggerAllSessionSummarization(1L)).isEqualTo(1);
        verify(summarizer).summarizeSession("own", 1L);
        verifyNoMoreInteractions(summarizer);
        verify(sessions, never()).findAll();
    }

    @Test void rejectsInvalidUserBeforeQuerying() {
        for (Long userId : new Long[]{null, 0L, -1L}) {
            assertThatThrownBy(() -> service.triggerAllSessionSummarization(userId))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getCode()).isEqualTo(403));
        }
        verifyNoInteractions(sessions, summarizer);
    }
}
