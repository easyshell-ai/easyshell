package com.easyshell.server.config;

import com.easyshell.server.security.JwtUtils;
import com.easyshell.server.security.AgentCredentialGenerationValidator;
import com.easyshell.server.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Authenticates every WebSocket handshake. Browsers cannot set request headers on
 * the WebSocket API, so the token is accepted from the {@code token} query parameter
 * or from the {@code Sec-WebSocket-Protocol} header ("bearer, &lt;jwt&gt;").
 *
 * <ul>
 *   <li>{@code /ws/terminal/*} and {@code /ws/task/log/*} require a user access token.</li>
 *   <li>{@code /ws/agent/*} requires an agent token whose subject equals the agentId in the path.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketAuthInterceptor implements HandshakeInterceptor {

    public static final String ATTR_USER_ID = "WS_USER_ID";
    public static final String ATTR_USERNAME = "WS_USERNAME";
    public static final String ATTR_ROLE = "WS_ROLE";
    public static final String ATTR_AGENT_ID = "WS_AGENT_ID";
    public static final String ATTR_BOUND_AGENT = "WS_BOUND_AGENT";
    public static final String ATTR_AGENT_AUTH_KIND = "WS_AGENT_AUTH_KIND";

    public enum AgentAuthKind { LEGACY, BOUND }

    public record BoundAgent(String agentId, long generation, java.time.Instant expiresAt) { }

    private final JwtUtils jwtUtils;
    private final UserRepository userRepository;
    private final AgentCredentialGenerationValidator generationValidator;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String path = request.getURI().getPath();
        String token = extractToken(request);
        if (token == null) {
            return reject(response, path, "missing token");
        }
        if (!jwtUtils.validateToken(token)) {
            return reject(response, path, "invalid token");
        }

        try {
            String type = jwtUtils.getTokenType(token);
            var claims = jwtUtils.parseToken(token);
            String subject = claims.getSubject();

            if (path != null && path.startsWith("/ws/agent/")) {
                BoundAgent boundAgent = null;
                if (AgentCredentialGenerationValidator.TOKEN_TYPE_BOUND_AGENT.equals(type)) {
                    var identity = generationValidator.validate(token).orElse(null);
                    if (identity == null || !identity.agentId().equals(subject) || claims.getExpiration() == null) {
                        return reject(response, path, "bound agent token rejected");
                    }
                    boundAgent = new BoundAgent(identity.agentId(), identity.generation(), claims.getExpiration().toInstant());
                } else if (!JwtUtils.TOKEN_TYPE_AGENT.equals(type) || !generationValidator.permitsLegacy(subject)) {
                    return reject(response, path, "agent token required");
                }
                String pathAgentId = lastSegment(path);
                if (pathAgentId == null || !pathAgentId.equals(subject)) {
                    return reject(response, path, "agent token subject mismatch");
                }
                attributes.put(ATTR_AGENT_ID, subject);
                attributes.put(ATTR_AGENT_AUTH_KIND, boundAgent == null ? AgentAuthKind.LEGACY : AgentAuthKind.BOUND);
                if (boundAgent != null) attributes.put(ATTR_BOUND_AGENT, boundAgent);
                return true;
            }

            if (type != null && !JwtUtils.TOKEN_TYPE_ACCESS.equals(type)) {
                return reject(response, path, "access token required");
            }
            Long userId = Long.valueOf(subject);
            var user = userRepository.findById(userId).orElse(null);
            if (user == null || !Integer.valueOf(1).equals(user.getStatus())
                    || !StringUtils.hasText(user.getRole()) || !StringUtils.hasText(user.getUsername())) {
                return reject(response, path, "user unavailable");
            }
            attributes.put(ATTR_USER_ID, userId);
            attributes.put(ATTR_USERNAME, user.getUsername());
            attributes.put(ATTR_ROLE, user.getRole());
            return true;
        } catch (Exception e) {
            return reject(response, path, "token rejected: " + e.getClass().getSimpleName());
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }

    private boolean reject(ServerHttpResponse response, String path, String reason) {
        log.warn("Rejected WebSocket handshake for {}: {}", path, reason);
        response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
        byte[] body = "unauthorized".getBytes(StandardCharsets.UTF_8);
        try {
            response.getBody().write(body);
        } catch (Exception ignored) {
            // best effort
        }
        return false;
    }

    private String extractToken(ServerHttpRequest request) {
        // Non-browser clients (e.g. the Go agent) can set real headers.
        var authHeaders = request.getHeaders().get(org.springframework.http.HttpHeaders.AUTHORIZATION);
        if (authHeaders != null) {
            for (String header : authHeaders) {
                if (header != null && header.startsWith("Bearer ") && header.length() > 7) {
                    return header.substring(7);
                }
            }
        }

        String query = request.getURI().getQuery();
        if (query != null) {
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && "token".equals(pair.substring(0, eq))) {
                    String value = pair.substring(eq + 1);
                    if (!value.isBlank()) {
                        return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
                    }
                }
            }
        }
        // Sec-WebSocket-Protocol: bearer, <token>
        var protocols = request.getHeaders().get("Sec-WebSocket-Protocol");
        if (protocols != null && !protocols.isEmpty()) {
            for (String header : protocols) {
                String[] parts = header.split(",");
                if (parts.length == 2 && "bearer".equalsIgnoreCase(parts[0].trim())) {
                    String value = parts[1].trim();
                    if (!value.isBlank()) {
                        return value;
                    }
                }
            }
        }
        return null;
    }

    private String lastSegment(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String[] parts = path.split("/");
        return parts.length > 0 ? parts[parts.length - 1] : null;
    }
}
