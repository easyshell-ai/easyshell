package com.easyshell.server.config;

import com.easyshell.server.security.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.WebSocketHandler;

import java.util.Map;

import static org.mockito.Mockito.mock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The interceptor is the only thing standing between an anonymous client and a root shell,
 * so every rejection path is covered here.
 */
class WebSocketAuthInterceptorTest {

    private static final String SECRET = "unit-test-jwt-secret-value-with-enough-entropy-0123456789";

    private JwtUtils jwtUtils;
    private WebSocketAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        jwtUtils = new JwtUtils();
        ReflectionTestUtils.setField(jwtUtils, "jwtSecret", SECRET);
        ReflectionTestUtils.setField(jwtUtils, "jwtExpire", 3_600_000L);
        ReflectionTestUtils.setField(jwtUtils, "refreshExpire", 604_800_000L);
        ReflectionTestUtils.setField(jwtUtils, "agentTokenExpire", 2_592_000_000L);
        interceptor = new WebSocketAuthInterceptor(jwtUtils);
    }

    @Test
    void noToken_isRejected() {
        assertThat(handshake("/ws/terminal/ag_demo_host_01", null, null, null))
                .isFalse();
    }

    @Test
    void invalidToken_isRejected() {
        assertThat(handshake("/ws/terminal/ag_demo_host_01", "garbage", null, null))
                .isFalse();
    }

    @Test
    void refreshToken_isRejected() {
        String token = jwtUtils.generateRefreshToken(7L);
        assertThat(handshake("/ws/terminal/ag_demo_host_01", token, null, null))
                .isFalse();
    }

    @Test
    void userToken_isRejectedOnAgentChannel() {
        String token = jwtUtils.generateAccessToken(7L, "alice", "admin");
        assertThat(handshake("/ws/agent/ag_demo_host_01", token, null, null))
                .isFalse();
    }

    @Test
    void agentToken_isRejectedOnUserChannel() {
        String token = jwtUtils.generateAgentToken("ag_demo_host_01");
        assertThat(handshake("/ws/terminal/ag_demo_host_01", token, null, null))
                .isFalse();
    }

    @Test
    void agentTokenForDifferentAgent_isRejected() {
        String token = jwtUtils.generateAgentToken("ag_other_host");
        assertThat(handshake("/ws/agent/ag_demo_host_01", token, null, null))
                .isFalse();
    }

    @Test
    void validAgentToken_isAccepted() {
        String token = jwtUtils.generateAgentToken("ag_demo_host_01");
        Map<String, Object> attributes = new java.util.HashMap<>();
        assertThat(handshake("/ws/agent/ag_demo_host_01", token, attributes, null)).isTrue();
        assertThat(attributes.get(WebSocketAuthInterceptor.ATTR_AGENT_ID))
                .isEqualTo("ag_demo_host_01");
    }

    @Test
    void validUserToken_isAccepted_withIdentity() {
        String token = jwtUtils.generateAccessToken(7L, "alice", "admin");
        Map<String, Object> attributes = new java.util.HashMap<>();
        assertThat(handshake("/ws/terminal/ag_demo_host_01", token, attributes, null)).isTrue();
        assertThat(attributes.get(WebSocketAuthInterceptor.ATTR_USER_ID)).isEqualTo(7L);
        assertThat(attributes.get(WebSocketAuthInterceptor.ATTR_USERNAME)).isEqualTo("alice");
        assertThat(attributes.get(WebSocketAuthInterceptor.ATTR_ROLE)).isEqualTo("admin");
    }

    @Test
    void tokenFromSubProtocolHeader_isAccepted() {
        String token = jwtUtils.generateAccessToken(7L, "alice", "admin");
        Map<String, Object> attributes = new java.util.HashMap<>();
        assertThat(handshake("/ws/terminal/ag_demo_host_01", null, attributes, token)).isTrue();
        assertThat(attributes.get(WebSocketAuthInterceptor.ATTR_USER_ID)).isEqualTo(7L);
    }

    private boolean handshake(String path, String queryToken, Map<String, Object> attributes,
                              String protocolToken) {
        String uri = path + (queryToken != null ? "?token=" + queryToken : "");
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", uri);
        if (protocolToken != null) {
            servletRequest.addHeader("Sec-WebSocket-Protocol", "bearer, " + protocolToken);
        }
        ServerHttpRequest request = new org.springframework.http.server.ServletServerHttpRequest(servletRequest);
        ServerHttpResponse response = new org.springframework.http.server.ServletServerHttpResponse(
                new MockHttpServletResponse());
        return interceptor.beforeHandshake(request, response, mock(WebSocketHandler.class),
                attributes != null ? attributes : new java.util.HashMap<>());
    }
}
