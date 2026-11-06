package com.easyshell.server.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtUtils {

    public static final String TOKEN_TYPE_ACCESS = "access";
    public static final String TOKEN_TYPE_REFRESH = "refresh";
    public static final String TOKEN_TYPE_AGENT = "agent";

    private static final int MIN_SECRET_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    @Value("${easyshell.auth.jwt-secret}")
    private String jwtSecret;

    @Value("${easyshell.auth.jwt-expire}")
    private long jwtExpire;

    @Value("${easyshell.auth.refresh-expire}")
    private long refreshExpire;

    @Value("${easyshell.auth.agent-token-expire:2592000000}")
    private long agentTokenExpire;

    @PostConstruct
    void validateSecret() {
        if (jwtSecret == null || jwtSecret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "easyshell.auth.jwt-secret must be set (env JWT_SECRET) and contain at least "
                            + MIN_SECRET_BYTES + " bytes; refusing to start with a weak or default secret");
        }
    }

    private SecretKey getSigningKey() {
        byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateAccessToken(Long userId, String username, String role) {
        return Jwts.builder()
                .subject(userId.toString())
                .claim("username", username)
                .claim("role", role)
                .claim("type", TOKEN_TYPE_ACCESS)
                .id(UUID.randomUUID().toString())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + jwtExpire))
                .signWith(getSigningKey())
                .compact();
    }

    public String generateRefreshToken(Long userId) {
        return Jwts.builder()
                .subject(userId.toString())
                .claim("type", TOKEN_TYPE_REFRESH)
                .id(UUID.randomUUID().toString())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + refreshExpire))
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * Agent credentials: the subject is the agentId (not a numeric user id).
     */
    public String generateAgentToken(String agentId) {
        return Jwts.builder()
                .subject(agentId)
                .claim("type", TOKEN_TYPE_AGENT)
                .id(UUID.randomUUID().toString())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + agentTokenExpire))
                .signWith(getSigningKey())
                .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public String getTokenType(String token) {
        return parseToken(token).get("type", String.class);
    }

    public String getTokenId(String token) {
        return parseToken(token).getId();
    }

    public Long getUserIdFromToken(String token) {
        return Long.parseLong(parseToken(token).getSubject());
    }

    public String getUsernameFromToken(String token) {
        return parseToken(token).get("username", String.class);
    }

    public String getRoleFromToken(String token) {
        return parseToken(token).get("role", String.class);
    }

    public long getJwtExpire() {
        return jwtExpire;
    }

    public long getRefreshExpire() {
        return refreshExpire;
    }
}
