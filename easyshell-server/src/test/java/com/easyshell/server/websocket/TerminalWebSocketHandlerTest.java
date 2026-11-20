package com.easyshell.server.websocket;

import com.easyshell.server.config.WebSocketAuthInterceptor;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.service.HostAclService;
import com.easyshell.server.service.HostAclService.Permission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TerminalWebSocketHandlerTest {
    private static final String AGENT = "ag_demo_host_01";
    private static final long USER = 42L;
    @Mock private AgentWebSocketHandler agentHandler;
    @Mock private HostAclService acl;
    @Mock private AuditLogService audit;
    private TerminalWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        handler = new TerminalWebSocketHandler(agentHandler, acl, audit);
    }

    private WebSocketSession session(String role) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(WebSocketAuthInterceptor.ATTR_USER_ID, USER);
        if (role != null) attributes.put(WebSocketAuthInterceptor.ATTR_ROLE, role);
        when(session.getAttributes()).thenReturn(attributes);
        lenient().when(session.getUri()).thenReturn(new URI("/ws/terminal/" + AGENT + "?token=fake"));
        return session;
    }

    private void allowOpen() {
        when(acl.authorizedGeneration(USER, AGENT, Permission.TERMINAL)).thenReturn(OptionalLong.of(3));
        when(acl.allowedForGeneration(USER, AGENT, 3, Permission.TERMINAL)).thenReturn(true);
        when(agentHandler.isAgentConnected(AGENT)).thenReturn(true);
        when(agentHandler.sendToAgent(eq(AGENT), anyString())).thenReturn(true);
    }

    private String open(WebSocketSession session) throws Exception {
        handler.afterConnectionEstablished(session);
        return (String) session.getAttributes().get("termSessionId");
    }

    private void denied(WebSocketSession session) throws Exception {
        verify(session).close(CloseStatus.POLICY_VIOLATION);
        verify(audit).logSync(eq(USER), anyString(), eq("WS_ACCESS_DENIED"), eq("agent"),
                eq(AGENT), anyString(), isNull(), eq("denied"));
    }

    @Test
    void migratedOperatorCanOpenWithoutTrustingTokenRole() throws Exception {
        allowOpen();
        WebSocketSession session = session("viewer");
        open(session);
        verify(agentHandler).sendToAgent(eq(AGENT), contains("terminal_open"));
        verify(acl).allowedForGeneration(USER, AGENT, 3, Permission.TERMINAL);
    }

    @Test
    void grantedAdministratorCanOpen() throws Exception {
        allowOpen();
        open(session("admin"));
        verify(agentHandler).sendToAgent(eq(AGENT), contains("terminal_open"));
    }

    @Test
    void ungrantedAdministratorIsDenied() throws Exception {
        WebSocketSession session = session("admin");
        when(acl.authorizedGeneration(USER, AGENT, Permission.TERMINAL)).thenReturn(OptionalLong.empty());
        open(session);
        denied(session);
        verify(agentHandler, never()).sendToAgent(anyString(), anyString());
    }

    @Test
    void databaseFailureIsDenied() throws Exception {
        WebSocketSession session = session("operator");
        when(acl.authorizedGeneration(USER, AGENT, Permission.TERMINAL)).thenThrow(new IllegalStateException());
        open(session);
        denied(session);
        verify(agentHandler, never()).sendToAgent(anyString(), anyString());
    }

    @Test
    void revocationStopsInputAndCloseMessages() throws Exception {
        allowOpen();
        WebSocketSession session = session("operator");
        open(session);
        when(acl.allowedForGeneration(USER, AGENT, 3, Permission.TERMINAL)).thenReturn(false);
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"input\",\"data\":\"id\"}"));
        handler.afterConnectionClosed(session, CloseStatus.POLICY_VIOLATION);
        denied(session);
        verify(agentHandler, times(1)).sendToAgent(eq(AGENT), anyString());
    }

    @Test
    void generationChangeOrDisabledUserStopsOutput() throws Exception {
        allowOpen();
        WebSocketSession session = session("operator");
        String id = open(session);
        when(session.isOpen()).thenReturn(true);
        when(acl.allowedForGeneration(USER, AGENT, 3, Permission.TERMINAL)).thenReturn(false);
        handler.handleAgentOutput(id, "secret");
        denied(session);
        verify(session, never()).sendMessage(any());
    }

    @Test
    void recheckFailureStopsReady() throws Exception {
        allowOpen();
        WebSocketSession session = session(null);
        String id = open(session);
        when(session.isOpen()).thenReturn(true);
        when(acl.allowedForGeneration(USER, AGENT, 3, Permission.TERMINAL)).thenThrow(new IllegalStateException());
        handler.handleAgentReady(id);
        denied(session);
        verify(session, never()).sendMessage(any());
    }

    @Test
    void resizeIsRechecked() throws Exception {
        allowOpen();
        WebSocketSession session = session("operator");
        open(session);
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"resize\",\"cols\":80,\"rows\":24}"));
        verify(acl, times(2)).allowedForGeneration(USER, AGENT, 3, Permission.TERMINAL);
        verify(agentHandler).sendToAgent(eq(AGENT), contains("terminal_resize"));
    }
}
