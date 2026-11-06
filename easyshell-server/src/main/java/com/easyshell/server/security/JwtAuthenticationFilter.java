package com.easyshell.server.security;

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

    private final JwtUtils jwtUtils;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request);

        if (StringUtils.hasText(token) && jwtUtils.validateToken(token)) {
            try {
                String type = jwtUtils.getTokenType(token);
                if (JwtUtils.TOKEN_TYPE_AGENT.equals(type)) {
                    String agentId = jwtUtils.parseToken(token).getSubject();
                    var authorities = List.of(new SimpleGrantedAuthority("ROLE_AGENT"));
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(agentId, null, authorities));
                    request.setAttribute(ATTR_AGENT_ID, agentId);
                } else if (type == null || JwtUtils.TOKEN_TYPE_ACCESS.equals(type)) {
                    Long userId = jwtUtils.getUserIdFromToken(token);
                    String username = jwtUtils.getUsernameFromToken(token);
                    String role = jwtUtils.getRoleFromToken(token);
                    if (role != null && userId != null) {
                        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()));
                        var authentication = new UsernamePasswordAuthenticationToken(userId, username, authorities);
                        SecurityContextHolder.getContext().setAuthentication(authentication);
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
