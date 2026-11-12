package com.easyshell.server.config;

import com.easyshell.server.security.JwtUtils;
import com.easyshell.server.security.AgentCredentialGenerationValidator;
import com.easyshell.server.model.entity.User;
import com.easyshell.server.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.WebSocketHandler;

import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The interceptor is the only thing standing between an anonymous client and a root shell,
 * so every rejection path is covered here.
 */
class WebSocketAuthInterceptorTest {

    private static final String SECRET = "unit-test-jwt-secret-value-with-enough-entropy-0123456789";

    private JwtUtils jwtUtils;
    private WebSocketAuthInterceptor interceptor;
    private UserRepository userRepository;
    private AgentCredentialGenerationValidator generationValidator;

    @BeforeEach
    void setUp() {
        jwtUtils = new JwtUtils();
        ReflectionTestUtils.setField(jwtUtils, "jwtSecret", SECRET);
        ReflectionTestUtils.setField(jwtUtils, "jwtExpire", 3_600_000L);
        ReflectionTestUtils.setField(jwtUtils, "refreshExpire", 604_800_000L);
        ReflectionTestUtils.setField(jwtUtils, "agentTokenExpire", 2_592_000_000L);
        userRepository = mock(UserRepository.class);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user("alice", "admin", 1)));
        generationValidator = mock(AgentCredentialGenerationValidator.class);
        when(generationValidator.permitsLegacy(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        interceptor = new WebSocketAuthInterceptor(jwtUtils, userRepository, generationValidator);
    }

    @Test
    void legacyAgentRejectedAfterBindingOrLedgerFailure() {
        String token = jwtUtils.generateAgentToken("host");
        when(generationValidator.permitsLegacy("host")).thenReturn(true, false)
                .thenThrow(new IllegalStateException("database unavailable"));
        assertThat(handshake("/ws/agent/host", token, null, null)).isTrue();
        assertThat(handshake("/ws/agent/host", token, null, null)).isFalse();
        assertThat(handshake("/ws/agent/host", token, null, null)).isFalse();
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
    void numericAgentSubject_isStillRejectedOnUserChannel() {
        String token = jwtUtils.generateAgentToken("7");
        assertThat(handshake("/ws/task/log/1", token, null, null)).isFalse();
        verifyNoInteractions(userRepository);
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
        assertThat(attributes.get(WebSocketAuthInterceptor.ATTR_AGENT_AUTH_KIND))
                .isEqualTo(WebSocketAuthInterceptor.AgentAuthKind.LEGACY);
        verifyNoInteractions(userRepository);
    }

    @Test
    void boundAgentRequiresCurrentGenerationAndMatchingPath() {
        String token = io.jsonwebtoken.Jwts.builder().subject("7")
                .claim("type", AgentCredentialGenerationValidator.TOKEN_TYPE_BOUND_AGENT)
                .claim(AgentCredentialGenerationValidator.GENERATION_CLAIM, 3)
                .expiration(new java.util.Date(System.currentTimeMillis() + 60_000))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .compact();
        when(generationValidator.validate(token)).thenReturn(
                Optional.of(new AgentCredentialGenerationValidator.Identity("7", 3)),
                Optional.of(new AgentCredentialGenerationValidator.Identity("7", 3)), Optional.empty())
                .thenThrow(new IllegalStateException("database unavailable"));
        Map<String, Object> attributes = new java.util.HashMap<>();
        assertThat(handshake("/ws/agent/7", token, attributes, null)).isTrue();
        assertThat(attributes.get(WebSocketAuthInterceptor.ATTR_AGENT_ID)).isEqualTo("7");
        assertThat(attributes.get(WebSocketAuthInterceptor.ATTR_AGENT_AUTH_KIND))
                .isEqualTo(WebSocketAuthInterceptor.AgentAuthKind.BOUND);
        var bound = (WebSocketAuthInterceptor.BoundAgent) attributes.get(WebSocketAuthInterceptor.ATTR_BOUND_AGENT);
        assertThat(bound.agentId()).isEqualTo("7");
        assertThat(bound.generation()).isEqualTo(3);
        assertThat(bound.expiresAt()).isEqualTo(jwtUtils.parseToken(token).getExpiration().toInstant());
        assertThat(handshake("/ws/agent/other", token, null, null)).isFalse();
        assertThat(handshake("/ws/agent/7", token, null, null)).isFalse();
        assertThat(handshake("/ws/agent/7", token, null, null)).isFalse();
        assertThat(handshake("/ws/task/log/1", token, null, null)).isFalse();
        verifyNoInteractions(userRepository);
    }

    @Test
    void oldToken_usesCurrentUserOnEveryHandshake() {
        String token = jwtUtils.generateAccessToken(7L, "old", "superadmin");
        when(userRepository.findById(7L)).thenReturn(
                Optional.of(user("alice", "viewer", 1)), Optional.of(user("renamed", "admin", 1)));
        Map<String, Object> attributes = new java.util.HashMap<>();
        assertThat(handshake("/ws/task/log/1", token, attributes, null)).isTrue();
        assertThat(attributes.get(WebSocketAuthInterceptor.ATTR_ROLE)).isEqualTo("viewer");
        attributes.clear();
        assertThat(handshake("/ws/task/log/1", token, attributes, null)).isTrue();
        assertThat(attributes.get(WebSocketAuthInterceptor.ATTR_ROLE)).isEqualTo("admin");
        assertThat(attributes.get(WebSocketAuthInterceptor.ATTR_USERNAME)).isEqualTo("renamed");
    }

    @Test
    void disabledDeletedAndDatabaseFailure_areRejected() {
        String token = jwtUtils.generateAccessToken(7L, "alice", "superadmin");
        when(userRepository.findById(7L)).thenReturn(Optional.of(user("alice", "superadmin", 0)))
                .thenReturn(Optional.empty()).thenThrow(new IllegalStateException("database unavailable"));
        for (int i = 0; i < 3; i++) {
            Map<String, Object> attributes = new java.util.HashMap<>();
            assertThat(handshake("/ws/task/log/1", token, attributes, null)).isFalse();
            assertThat(attributes).isEmpty();
        }
    }

    private User user(String username, String role, int status) {
        User user = new User();
        user.setUsername(username);
        user.setRole(role);
        user.setStatus(status);
        return user;
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
