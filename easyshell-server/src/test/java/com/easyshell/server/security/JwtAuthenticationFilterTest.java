package com.easyshell.server.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.WebSocketHandler;

import jakarta.servlet.FilterChain;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JwtAuthenticationFilterTest {

    private JwtUtils jwtUtils;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        jwtUtils = new JwtUtils();
        ReflectionTestUtils.setField(jwtUtils, "jwtSecret",
                "unit-test-jwt-secret-value-with-enough-entropy-0123456789");
        ReflectionTestUtils.setField(jwtUtils, "jwtExpire", 3_600_000L);
        ReflectionTestUtils.setField(jwtUtils, "refreshExpire", 604_800_000L);
        ReflectionTestUtils.setField(jwtUtils, "agentTokenExpire", 2_592_000_000L);
        filter = new JwtAuthenticationFilter(jwtUtils);
        SecurityContextHolder.clearContext();
    }

    @Test
    void accessToken_authenticatesUser() throws Exception {
        String token = jwtUtils.generateAccessToken(7L, "alice", "admin");

        invokeWithToken(token);

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isEqualTo(7L);
        // the username lives in the credentials slot, which is why AuditUsers exists
        assertThat(auth.getCredentials()).isEqualTo("alice");
        assertThat(AuditUsers.name(auth)).isEqualTo("alice");
        assertThat(auth.getAuthorities())
                .extracting(a -> a.getAuthority())
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    void refreshToken_isRejectedAsAccessToken() throws Exception {
        String token = jwtUtils.generateRefreshToken(7L);

        invokeWithToken(token);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void agentToken_authenticatesAgentSubject() throws Exception {
        String token = jwtUtils.generateAgentToken("ag_demo_host_01");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isEqualTo("ag_demo_host_01");
        assertThat(auth.getAuthorities())
                .extracting(a -> a.getAuthority())
                .containsExactly("ROLE_AGENT");
        assertThat(request.getAttribute(JwtAuthenticationFilter.ATTR_AGENT_ID))
                .isEqualTo("ag_demo_host_01");
    }

    @Test
    void garbageToken_isIgnored() throws Exception {
        invokeWithToken("not-a-jwt");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void missingToken_isAnonymous() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void agentTokenSubject_isNotNumeric() {
        String token = jwtUtils.generateAgentToken("ag_x");
        assertThat(jwtUtils.getTokenType(token)).isEqualTo(JwtUtils.TOKEN_TYPE_AGENT);
        assertThat(jwtUtils.parseToken(token).getSubject()).isEqualTo("ag_x");
        assertThatThrownBy(() -> jwtUtils.getUserIdFromToken(token))
                .isInstanceOf(NumberFormatException.class);
    }

    @Test
    void shortSecret_isRejected() {
        JwtUtils weak = new JwtUtils();
        ReflectionTestUtils.setField(weak, "jwtSecret", "too-short");
        assertThatThrownBy(weak::validateSecret).isInstanceOf(IllegalStateException.class);
    }

    private void invokeWithToken(String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));
    }
}
