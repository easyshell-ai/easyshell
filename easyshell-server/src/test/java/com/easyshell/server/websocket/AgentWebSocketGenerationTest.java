package com.easyshell.server.websocket;

import com.easyshell.server.config.WebSocketAuthInterceptor;
import com.easyshell.server.repository.JobRepository;
import com.easyshell.server.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentWebSocketGenerationTest {
    private TaskService tasks;
    private JdbcTemplate jdbc;
    private AgentWebSocketHandler handler;
    private WebSocketSession session;
    private Map<String, Object> attributes;

    @BeforeEach
    void setup() {
        tasks = mock(TaskService.class);
        jdbc = mock(JdbcTemplate.class);
        handler = new AgentWebSocketHandler(tasks, mock(JobRepository.class), jdbc);
        session = mock(WebSocketSession.class);
        attributes = new HashMap<>();
        attributes.put(WebSocketAuthInterceptor.ATTR_AGENT_ID, "host");
        attributes.put(WebSocketAuthInterceptor.ATTR_AGENT_AUTH_KIND, WebSocketAuthInterceptor.AgentAuthKind.LEGACY);
        when(session.getAttributes()).thenReturn(attributes);
        when(session.isOpen()).thenReturn(true);
        handler.afterConnectionEstablished(session);
    }

    private void bound(Instant expiry) {
        attributes.put(WebSocketAuthInterceptor.ATTR_AGENT_AUTH_KIND, WebSocketAuthInterceptor.AgentAuthKind.BOUND);
        attributes.put(WebSocketAuthInterceptor.ATTR_BOUND_AGENT,
                new WebSocketAuthInterceptor.BoundAgent("host", 3, expiry));
    }

    @Test
    void currentIdentityAllowsTraffic() throws Exception {
        Instant expiry = Instant.now().plusSeconds(60);
        bound(expiry);
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
                .thenReturn(List.of(new WebSocketAuthInterceptor.BoundAgent("host", 3, expiry)));
        assertThat(handler.sendToAgent("host", "{}")).isTrue();
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"log\",\"jobId\":\"j\",\"log\":\"x\"}"));
        verify(session).sendMessage(any());
        verify(tasks).appendJobLog("j", "x", "host");
    }

    @Test
    void retiredIdentityRejectsIncomingMessageAndRemovesConnection() throws Exception {
        bound(Instant.now().plusSeconds(60));
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any())).thenReturn(List.of());
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"log\",\"jobId\":\"j\",\"log\":\"x\"}"));
        verify(tasks, never()).appendJobLog(anyString(), anyString(), anyString());
        verify(session).close(CloseStatus.POLICY_VIOLATION);
        assertThat(handler.isAgentConnected("host")).isFalse();
    }

    @Test
    void expiryRejectsOutgoingMessageWithoutDatabaseLookup() throws Exception {
        bound(Instant.now().minusSeconds(1));
        assertThat(handler.sendToAgent("host", "{}")).isFalse();
        verifyNoInteractions(jdbc);
        verify(session).close(CloseStatus.POLICY_VIOLATION);
        verify(session, never()).sendMessage(any());
        assertThat(handler.isAgentConnected("host")).isFalse();
    }

    @Test
    void databaseFailureRejectsOutgoingMessage() throws Exception {
        bound(Instant.now().plusSeconds(60));
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
                .thenThrow(new IllegalStateException());
        assertThat(handler.sendToAgent("host", "{}")).isFalse();
        verify(session).close(CloseStatus.POLICY_VIOLATION);
        verify(session, never()).sendMessage(any());
        assertThat(handler.isAgentConnected("host")).isFalse();
    }

    @Test
    void legacyConnectionStillSendsAndReceives() throws Exception {
        assertThat(handler.sendToAgent("host", "{}")).isTrue();
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"log\",\"jobId\":\"j\",\"log\":\"x\"}"));
        verify(session).sendMessage(any());
        verify(tasks).appendJobLog("j", "x", "host");
        verify(jdbc, times(2)).query(contains("agent_bound_key"), any(RowMapper.class), eq("host"));
    }

    @Test
    void legacyBindingOrLedgerFailureStopsTraffic() throws Exception {
        when(jdbc.query(contains("agent_bound_key"), any(RowMapper.class), eq("host")))
                .thenReturn(List.of("host"));
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"log\",\"jobId\":\"j\",\"log\":\"x\"}"));
        verify(tasks, never()).appendJobLog(anyString(), anyString(), anyString());
        verify(session).close(CloseStatus.POLICY_VIOLATION);
        assertThat(handler.sendToAgent("host", "{}")).isFalse();
    }

    @Test
    void legacyLedgerFailureAndMissingMarkerFailClosed() throws Exception {
        when(jdbc.query(contains("agent_bound_key"), any(RowMapper.class), eq("host")))
                .thenThrow(new IllegalStateException());
        assertThat(handler.sendToAgent("host", "{}")).isFalse();
        verify(session).close(CloseStatus.POLICY_VIOLATION);

        WebSocketSession other = mock(WebSocketSession.class);
        attributes.remove(WebSocketAuthInterceptor.ATTR_AGENT_AUTH_KIND);
        when(other.getAttributes()).thenReturn(attributes);
        when(other.isOpen()).thenReturn(true);
        handler.afterConnectionEstablished(other);
        assertThat(handler.sendToAgent("host", "{}")).isFalse();
        verify(other).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void duplicateAndStaleSessionsCannotSendOrFailReplacementJobs() throws Exception {
        WebSocketSession duplicate = mock(WebSocketSession.class);
        when(duplicate.getAttributes()).thenReturn(new HashMap<>(attributes));
        when(duplicate.isOpen()).thenReturn(true);
        handler.afterConnectionEstablished(duplicate);
        handler.handleTextMessage(duplicate, new TextMessage("{\"type\":\"log\",\"jobId\":\"j\",\"log\":\"x\"}"));
        verify(tasks, never()).appendJobLog(anyString(), anyString(), anyString());
        verify(tasks, never()).failRunningJobsForAgent(anyString(), anyString());

        when(session.isOpen()).thenReturn(false);
        WebSocketSession replacement = mock(WebSocketSession.class);
        when(replacement.getAttributes()).thenReturn(new HashMap<>(attributes));
        when(replacement.isOpen()).thenReturn(true);
        handler.afterConnectionEstablished(replacement);
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        verify(tasks, never()).failRunningJobsForAgent(anyString(), anyString());
        assertThat(handler.sendToAgent("host", "{}")).isTrue();
        verify(replacement).sendMessage(any());
    }
}
