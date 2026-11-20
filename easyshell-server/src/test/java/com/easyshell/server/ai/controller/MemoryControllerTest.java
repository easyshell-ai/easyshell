package com.easyshell.server.ai.controller;

import com.easyshell.server.ai.model.entity.AiChatSession;
import com.easyshell.server.ai.model.entity.AiSessionSummary;
import com.easyshell.server.ai.repository.AiChatSessionRepository;
import com.easyshell.server.ai.repository.AiSessionSummaryRepository;
import com.easyshell.server.ai.service.AiChatService;
import com.easyshell.server.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MemoryControllerTest {
    @Mock private AiSessionSummaryRepository summaries;
    @Mock private AiChatSessionRepository sessions;
    @Mock private AiChatService chatService;
    private MemoryController controller;
    private final Authentication owner = auth(1L, "ROLE_USER");
    private final Authentication admin = auth(2L, "ROLE_ADMIN");

    private static Authentication auth(Long id, String role) {
        return new UsernamePasswordAuthenticationToken(id, null, List.of(new SimpleGrantedAuthority(role)));
    }

    private static AiSessionSummary summary(Long id, Long userId, String sessionId) {
        AiSessionSummary summary = new AiSessionSummary();
        summary.setId(id);
        summary.setUserId(userId);
        summary.setSessionId(sessionId);
        return summary;
    }

    private static void forbidden(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode()).isEqualTo(403));
    }

    @BeforeEach void setUp() {
        controller = new MemoryController(summaries, sessions, chatService);
    }

    @Test void listIsScopedAndPagedForBothUsersAndAdmins() {
        AiSessionSummary first = summary(1L, 1L, "a");
        AiSessionSummary second = summary(2L, 1L, "b");
        when(summaries.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(first, second));
        when(summaries.findByUserIdOrderByCreatedAtDesc(2L)).thenReturn(List.of());
        assertThat(controller.list(1, 1, owner).getData().getContent()).containsExactly(second);
        assertThat(controller.list(0, 20, admin).getData().getContent()).isEmpty();
        verify(summaries, never()).findAll(any(org.springframework.data.domain.Pageable.class));
    }

    @Test void detailAndDeleteRequireOwnerEvenForAdminAndMissingOwner() {
        when(summaries.findById(1L)).thenReturn(Optional.of(summary(1L, 1L, "a")));
        when(summaries.findById(2L)).thenReturn(Optional.of(summary(2L, null, "b")));
        assertThat(controller.getById(1L, owner).getCode()).isEqualTo(200);
        Authentication other = auth(2L, "ROLE_USER");
        forbidden(() -> controller.getById(1L, other));
        forbidden(() -> controller.delete(1L, other));
        forbidden(() -> controller.getById(1L, admin));
        forbidden(() -> controller.delete(1L, admin));
        forbidden(() -> controller.getById(2L, owner));
        forbidden(() -> controller.delete(2L, owner));
        verify(summaries, never()).deleteById(anyLong());
    }

    @Test void deleteDoesNotResetAnotherUsersSession() {
        when(summaries.findById(1L)).thenReturn(Optional.of(summary(1L, 1L, "a")));
        AiChatSession other = new AiChatSession();
        other.setUserId(2L);
        other.setSummaryGenerated(true);
        when(sessions.findById("a")).thenReturn(Optional.of(other));
        assertThat(controller.delete(1L, owner).getCode()).isEqualTo(200);
        verify(summaries).deleteById(1L);
        verify(sessions, never()).save(any());
        assertThat(other.getSummaryGenerated()).isTrue();
    }

    @Test void clearOnlyDeletesAndResetsOwnData() {
        AiSessionSummary own = summary(1L, 1L, "a");
        AiChatSession session = new AiChatSession();
        session.setUserId(1L);
        session.setSummaryGenerated(true);
        when(summaries.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(own));
        when(sessions.findByUserIdOrderByUpdatedAtDesc(1L)).thenReturn(List.of(session));
        assertThat(controller.clearAll(owner).getCode()).isEqualTo(200);
        verify(summaries).delete(own);
        verify(summaries, never()).deleteAll();
        verify(sessions).save(session);
        verify(sessions, never()).findAll();
        assertThat(session.getSummaryGenerated()).isFalse();
    }

    @Test void missingOrUnauthenticatedIdentityIsRejectedBeforeAccess() {
        Authentication unauthenticated = new UsernamePasswordAuthenticationToken(1L, null);
        forbidden(() -> controller.list(0, 20, null));
        forbidden(() -> controller.getById(1L, unauthenticated));
        forbidden(() -> controller.delete(1L, null));
        forbidden(() -> controller.clearAll(null));
        verifyNoInteractions(summaries, sessions);
    }

    @Test void absentMemoryRetainsOriginalResponse() {
        when(summaries.findById(99L)).thenReturn(Optional.empty());
        assertThat(controller.getById(99L, owner).getCode()).isNotEqualTo(200);
        assertThat(controller.delete(99L, owner).getCode()).isNotEqualTo(200);
    }

    @Test void triggerSummarizationUsesAuthenticatedUserAndOwnCount() {
        when(chatService.triggerAllSessionSummarization(1L)).thenReturn(2);
        when(chatService.triggerAllSessionSummarization(2L)).thenReturn(0);
        assertThat(controller.triggerSummarization(owner).getData()).isEqualTo("Triggered summarization for 2 sessions");
        assertThat(controller.triggerSummarization(admin).getData()).isEqualTo("Triggered summarization for 0 sessions");
        verify(chatService).triggerAllSessionSummarization(1L);
        verify(chatService).triggerAllSessionSummarization(2L);
    }

    @Test void triggerSummarizationRejectsInvalidIdentity() {
        forbidden(() -> controller.triggerSummarization(null));
        forbidden(() -> controller.triggerSummarization(new UsernamePasswordAuthenticationToken(1L, null)));
        forbidden(() -> controller.triggerSummarization(new UsernamePasswordAuthenticationToken("1", null, List.of())));
        forbidden(() -> controller.triggerSummarization(auth(0L, "ROLE_USER")));
        forbidden(() -> controller.triggerSummarization(auth(-1L, "ROLE_USER")));
        verifyNoInteractions(chatService, summaries, sessions);
    }
}
