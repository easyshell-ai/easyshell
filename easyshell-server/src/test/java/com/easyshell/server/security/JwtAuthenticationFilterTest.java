package com.easyshell.server.security;

import com.easyshell.server.model.entity.User;
import com.easyshell.server.repository.UserRepository;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

class JwtAuthenticationFilterTest {

    private JwtUtils jwtUtils;
    private JwtAuthenticationFilter filter;
    private UserRepository userRepository;
    private AgentCredentialGenerationValidator generationValidator;

    @BeforeEach
    void setUp() {
        jwtUtils = new JwtUtils();
        ReflectionTestUtils.setField(jwtUtils, "jwtSecret",
                "unit-test-jwt-secret-value-with-enough-entropy-0123456789");
        ReflectionTestUtils.setField(jwtUtils, "jwtExpire", 3_600_000L);
        ReflectionTestUtils.setField(jwtUtils, "refreshExpire", 604_800_000L);
        ReflectionTestUtils.setField(jwtUtils, "agentTokenExpire", 2_592_000_000L);
        userRepository = mock(UserRepository.class);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user("alice", "admin", 1)));
        generationValidator = mock(AgentCredentialGenerationValidator.class);
        when(generationValidator.permitsLegacy(any())).thenReturn(true);
        filter = new JwtAuthenticationFilter(jwtUtils, userRepository, generationValidator);
        SecurityContextHolder.clearContext();
    }

    @Test
    void legacyAgentRejectedAfterBindingOrLedgerFailure() throws Exception {
        String token = jwtUtils.generateAgentToken("host");
        when(generationValidator.permitsLegacy("host")).thenReturn(true, false)
                .thenThrow(new IllegalStateException("database unavailable"));
        invokeWithToken(token);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        invokeWithToken(token);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        invokeWithToken(token);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
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
        verifyNoInteractions(userRepository);
    }

    @Test
    void numericAgentSubject_isNotAuthenticatedAsUser() throws Exception {
        invokeWithToken(jwtUtils.generateAgentToken("7"));
        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth.getPrincipal()).isEqualTo("7");
        assertThat(auth.getAuthorities()).extracting(a -> a.getAuthority()).containsExactly("ROLE_AGENT");
        verifyNoInteractions(userRepository);
    }

    @Test
    void boundAgentRequiresCurrentGenerationAndNeverBecomesUser() throws Exception {
        String token = boundToken("7");
        when(generationValidator.validate(token)).thenReturn(
                Optional.of(new AgentCredentialGenerationValidator.Identity("7", 3)), Optional.empty())
                .thenThrow(new IllegalStateException("database unavailable"));
        invokeWithToken(token);
        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth.getPrincipal()).isEqualTo("7");
        assertThat(auth.getAuthorities()).extracting(a -> a.getAuthority()).containsExactly("ROLE_AGENT");
        for (int i = 0; i < 2; i++) {
            invokeWithToken(token);
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }
        verifyNoInteractions(userRepository);
    }

    private String boundToken(String subject) {
        return io.jsonwebtoken.Jwts.builder().subject(subject)
                .claim("type", AgentCredentialGenerationValidator.TOKEN_TYPE_BOUND_AGENT)
                .claim(AgentCredentialGenerationValidator.GENERATION_CLAIM, 3)
                .expiration(new java.util.Date(System.currentTimeMillis() + 60_000))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        "unit-test-jwt-secret-value-with-enough-entropy-0123456789".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .compact();
    }

    @Test
    void oldToken_usesCurrentRoleAndUsernameOnEveryRequest() throws Exception {
        String token = jwtUtils.generateAccessToken(7L, "old", "superadmin");
        when(userRepository.findById(7L)).thenReturn(
                Optional.of(user("alice", "viewer", 1)), Optional.of(user("renamed", "admin", 1)));
        invokeWithToken(token);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(a -> a.getAuthority()).containsExactly("ROLE_VIEWER");
        SecurityContextHolder.clearContext();
        invokeWithToken(token);
        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth.getCredentials()).isEqualTo("renamed");
        assertThat(auth.getAuthorities()).extracting(a -> a.getAuthority()).containsExactly("ROLE_ADMIN");
    }

    @Test
    void disabledDeletedAndDatabaseFailure_areAnonymous() throws Exception {
        String token = jwtUtils.generateAccessToken(7L, "alice", "superadmin");
        when(userRepository.findById(7L)).thenReturn(Optional.of(user("alice", "superadmin", 0)))
                .thenReturn(Optional.empty()).thenThrow(new IllegalStateException("database unavailable"));
        for (int i = 0; i < 3; i++) {
            invokeWithToken(token);
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
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
