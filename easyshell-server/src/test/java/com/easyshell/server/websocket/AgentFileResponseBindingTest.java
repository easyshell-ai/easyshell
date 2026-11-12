package com.easyshell.server.websocket;

import com.easyshell.server.config.WebSocketAuthInterceptor;
import com.easyshell.server.repository.JobRepository;
import com.easyshell.server.service.FileProxyService;
import com.easyshell.server.service.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.Mockito.*;

class AgentFileResponseBindingTest {
    @Test
    void fileResponsesUseAuthenticatedIdentityNotPayloadOrPath() {
        AgentWebSocketHandler handler = new AgentWebSocketHandler(mock(TaskService.class), mock(JobRepository.class), mock(org.springframework.jdbc.core.JdbcTemplate.class));
        FileProxyService service = mock(FileProxyService.class);
        handler.setFileProxyService(service);
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(WebSocketAuthInterceptor.ATTR_AGENT_ID, "attacker");
        attributes.put(WebSocketAuthInterceptor.ATTR_AGENT_AUTH_KIND, WebSocketAuthInterceptor.AgentAuthKind.LEGACY);
        when(session.getAttributes()).thenReturn(attributes);
        when(session.isOpen()).thenReturn(true);
        when(session.getUri()).thenReturn(URI.create("ws://localhost/ws/agent/owner"));
        handler.afterConnectionEstablished(session);
        String payload = "{\"type\":\"file_result\",\"requestId\":\"r\",\"agentId\":\"owner\"}";

        handler.handleTextMessage(session, new TextMessage(payload));
        verify(service).handleFileResponse("attacker", "r", payload);

        attributes.clear();
        handler.handleTextMessage(session, new TextMessage(payload));
        verifyNoMoreInteractions(service);
    }
}
