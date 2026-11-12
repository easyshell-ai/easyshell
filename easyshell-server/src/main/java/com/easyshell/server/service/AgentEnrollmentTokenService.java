package com.easyshell.server.service;

import com.easyshell.server.repository.AgentEnrollmentTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Unwired enrollment primitive. Caller must hold the HostIdentity lock in the same transaction
 * and validate the actual identity, generation, initiator and authorization before either operation.
 * Issuing revokes all previously unconsumed tokens for the agent; successful consumption revokes
 * its other unconsumed tokens. Both operations roll back with the caller's transaction on failure.
 * Never log or persist the returned bearer token.
 */
@Service
public class AgentEnrollmentTokenService {
    private final AgentEnrollmentTokenRepository tokens;
    private final SecureRandom random = new SecureRandom();

    public AgentEnrollmentTokenService(AgentEnrollmentTokenRepository tokens) { this.tokens = tokens; }

    @Transactional(propagation = Propagation.MANDATORY)
    public String issue(String agentId, long generation, long initiatedBy, Instant expiresAt) {
        if (agentId == null || agentId.isBlank() || generation <= 0 || initiatedBy <= 0 || expiresAt == null
                || !expiresAt.isAfter(Instant.now())) throw new IllegalArgumentException("Invalid enrollment parameters");
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.revokeUnconsumed(agentId);
        tokens.insert(digest(token), agentId, generation, initiatedBy, expiresAt);
        return token;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean consume(String token, String agentId, long generation) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}") || agentId == null || agentId.isBlank()
                || generation <= 0) return false;
        if (!tokens.consume(digest(token), agentId, generation)) return false;
        tokens.revokeUnconsumed(agentId);
        return true;
    }

    private static String digest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
