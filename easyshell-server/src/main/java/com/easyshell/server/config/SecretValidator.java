package com.easyshell.server.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;

/**
 * Refuses to start when secrets are missing or too weak. The previous version shipped
 * hardcoded fallback values in application.yml, which meant any non-compose startup
 * (bootRun, plain jar) silently used publicly known keys.
 */
@Slf4j
@Component
public class SecretValidator {

    private static final int MIN_JWT_SECRET_BYTES = 32;
    private static final int MIN_ENCRYPTION_KEY_CHARS = 16;

    @Value("${easyshell.auth.jwt-secret:}")
    private String jwtSecret;

    @Value("${easyshell.security.encryption-key:}")
    private String encryptionKey;

    @PostConstruct
    public void validate() {
        requireSecret("JWT_SECRET", jwtSecret, MIN_JWT_SECRET_BYTES);

        if (encryptionKey == null || encryptionKey.isBlank()) {
            throw new IllegalStateException(
                    "ENCRYPTION_KEY is not set - refusing to start without an encryption key");
        }
        if (encryptionKey.length() < MIN_ENCRYPTION_KEY_CHARS) {
            throw new IllegalStateException(
                    "ENCRYPTION_KEY must be at least " + MIN_ENCRYPTION_KEY_CHARS + " characters");
        }
        log.info("Secret validation passed (JWT secret {} bytes, encryption key {} chars)",
                jwtSecret.getBytes(StandardCharsets.UTF_8).length, encryptionKey.length());
    }

    private void requireSecret(String name, String value, int minBytes) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not set - refusing to start without it");
        }
        if (value.getBytes(StandardCharsets.UTF_8).length < minBytes) {
            throw new IllegalStateException(
                    name + " must contain at least " + minBytes + " " + "bytes of key material");
        }
    }
}
