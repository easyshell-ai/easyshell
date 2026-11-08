package com.easyshell.server.websocket;

import com.easyshell.server.config.WebSocketAuthInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B4 存量越权回归（终端通道）：/ws/terminal/{agentId} 曾只校验"已登录"，
 * 任何登录用户都能打开任意主机的交互 shell。修复后按 RBAC 收敛：
 * viewer（只读角色）与未知角色一律拒绝，operator 及以上放行。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TerminalWebSocketHandler — 终端通道的 RBAC 授权")
class TerminalWebSocketHandlerTest {

    private static final String AGENT_ID = "ag_demo_host_01";
    private static final String PATH = "/ws/terminal/" + AGENT_ID + "?token=fake";

    @Mock
    private AgentWebSocketHandler agentHandler;

    private TerminalWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        handler = new TerminalWebSocketHandler(agentHandler);
    }

    private WebSocketSession session(Long userId, String role) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        if (userId != null) {
            attributes.put(WebSocketAuthInterceptor.ATTR_USER_ID, userId);
        }
        if (role != null) {
            attributes.put(WebSocketAuthInterceptor.ATTR_ROLE, role);
        }
        when(session.getAttributes()).thenReturn(attributes);
        lenient().when(session.getUri()).thenReturn(new URI(PATH));
        return session;
    }

    @Test
    @DisplayName("viewer 打开终端被拒：POLICY_VIOLATION，且不向 agent 发 terminal_open")
    void viewer_isRejected() throws Exception {
        WebSocketSession viewer = session(42L, "viewer");

        handler.afterConnectionEstablished(viewer);

        verify(viewer).close(CloseStatus.POLICY_VIOLATION);
        verify(agentHandler, never()).sendToAgent(anyString(), any());
    }

    @Test
    @DisplayName("角色缺失/未知一律拒绝（fail-closed）")
    void missingOrUnknownRole_isRejected() throws Exception {
        WebSocketSession noRole = session(42L, null);
        handler.afterConnectionEstablished(noRole);
        verify(noRole).close(CloseStatus.POLICY_VIOLATION);

        WebSocketSession weirdRole = session(42L, "root_of_all_evil");
        handler.afterConnectionEstablished(weirdRole);
        verify(weirdRole).close(CloseStatus.POLICY_VIOLATION);
        verify(agentHandler, never()).sendToAgent(anyString(), any());
    }

    @Test
    @DisplayName("operator 放行：向 agent 发送 terminal_open 并登记会话")
    void operator_isAllowed() throws Exception {
        when(agentHandler.isAgentConnected(AGENT_ID)).thenReturn(true);
        when(agentHandler.sendToAgent(anyString(), any())).thenReturn(true);
        WebSocketSession operator = session(42L, "operator");

        handler.afterConnectionEstablished(operator);

        verify(operator, never()).close(any());
        verify(agentHandler).sendToAgent(anyString(), org.mockito.ArgumentMatchers.argThat(
                msg -> msg.contains("terminal_open") && msg.contains("sessionId")));
    }

    @Test
    @DisplayName("admin / super_admin 放行")
    void admins_areAllowed() throws Exception {
        when(agentHandler.isAgentConnected(AGENT_ID)).thenReturn(true);
        when(agentHandler.sendToAgent(anyString(), any())).thenReturn(true);

        handler.afterConnectionEstablished(session(7L, "admin"));
        handler.afterConnectionEstablished(session(8L, "super_admin"));

        verify(agentHandler, org.mockito.Mockito.times(2))
                .sendToAgent(anyString(), org.mockito.ArgumentMatchers.argThat(
                        msg -> msg.contains("terminal_open")));
    }

    @Test
    @DisplayName("未认证会话仍然拒绝")
    void unauthenticated_isRejected() throws Exception {
        WebSocketSession anonymous = session(null, null);

        handler.afterConnectionEstablished(anonymous);

        verify(anonymous).close(CloseStatus.POLICY_VIOLATION);
    }
}
