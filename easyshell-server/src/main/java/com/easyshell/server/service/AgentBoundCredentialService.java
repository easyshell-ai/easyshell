package com.easyshell.server.service;

import com.easyshell.server.security.JwtUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Trusted issuance primitive only; this service does not expose an authentication endpoint. */
@Service
public class AgentBoundCredentialService {
    private final AgentBoundKeyService keys;
    private final JwtUtils jwt;
    private final TransactionTemplate transaction;

    public AgentBoundCredentialService(AgentBoundKeyService keys, JwtUtils jwt, PlatformTransactionManager manager) {
        this.keys = keys;
        this.jwt = jwt;
        this.transaction = new TransactionTemplate(manager);
        // Do not return a credential whose challenge consumption is still awaiting an outer commit.
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Invalid or replayed proofs fail closed. Signing and consumption commit together. */
    public String issue(String agentId, long generation, String challenge, byte[] signature) {
        return transaction.execute(status -> {
            if (!keys.verify(agentId, generation, challenge, signature)) {
                throw new IllegalArgumentException("Invalid agent proof");
            }
            String token = jwt.generateBoundAgentToken(agentId, generation);
            if (token == null || token.isBlank()) throw new IllegalStateException("Agent token signing failed");
            return token;
        });
    }
}
