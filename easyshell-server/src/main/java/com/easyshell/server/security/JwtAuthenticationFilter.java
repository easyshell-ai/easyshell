package com.easyshell.server.security;

import com.easyshell.server.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Authenticates both user tokens (Authorization: Bearer &lt;access token&gt;) and
 * agent tokens (subject = agentId, type = "agent").
 *
 * <p>Refresh tokens are deliberately rejected here: they must only ever be used
 * against {@code /api/v1/auth/refresh}.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String ATTR_AGENT_ID = "AGENT_ID";
    public static final String ATTR_BOUND_AGENT_TOKEN = "BOUND_AGENT_TOKEN";

    private final JwtUtils jwtUtils;
    private final UserRepository userRepository;
    private final AgentCredentialGenerationValidator generationValidator;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request);

        if (StringUtils.hasText(token) && jwtUtils.validateToken(token)) {
            try {
                String type = jwtUtils.getTokenType(token);
                if (AgentCredentialGenerationValidator.TOKEN_TYPE_BOUND_AGENT.equals(type)) {
                    request.setAttribute(ATTR_BOUND_AGENT_TOKEN, Boolean.TRUE);
                    var identity = generationValidator.validate(token).orElse(null);
                    if (identity == null) {
                        SecurityContextHolder.clearContext();
                    } else {
                        var authorities = List.of(new SimpleGrantedAuthority("ROLE_AGENT"));
                        SecurityContextHolder.getContext().setAuthentication(
                                new UsernamePasswordAuthenticationToken(identity.agentId(), null, authorities));
                        request.setAttribute(ATTR_AGENT_ID, identity.agentId());
                    }
                } else if (JwtUtils.TOKEN_TYPE_AGENT.equals(type)) {
                    String agentId = jwtUtils.parseToken(token).getSubject();
                    if (!generationValidator.permitsLegacy(agentId)) {
                        SecurityContextHolder.clearContext();
                    } else {
                        var authorities = List.of(new SimpleGrantedAuthority("ROLE_AGENT"));
                        SecurityContextHolder.getContext().setAuthentication(
                                new UsernamePasswordAuthenticationToken(agentId, null, authorities));
                        request.setAttribute(ATTR_AGENT_ID, agentId);
                    }
                } else if (type == null || JwtUtils.TOKEN_TYPE_ACCESS.equals(type)) {
                    Long userId = jwtUtils.getUserIdFromToken(token);
                    var user = userRepository.findById(userId).orElse(null);
                    if (user != null && Integer.valueOf(1).equals(user.getStatus())
                            && StringUtils.hasText(user.getRole()) && StringUtils.hasText(user.getUsername())) {
                        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().toUpperCase(Locale.ROOT)));
                        var authentication = new UsernamePasswordAuthenticationToken(userId, user.getUsername(), authorities);
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    } else {
                        SecurityContextHolder.clearContext();
                    }
                }
                // refresh tokens fall through unauthenticated
            } catch (Exception e) {
                // Malformed subject/claims: treat as anonymous rather than 500
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
