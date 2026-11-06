package com.easyshell.server.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.Set;

/**
 * Server-side registry of the refresh tokens that are still valid.
 *
 * <p>Refresh tokens are rotated on every use: the presented token id must still exist, and it is
 * consumed atomically, which makes replay impossible. Deleting all ids for a user (password
 * change, account disable) revokes every refresh token that was issued for them.
 */
@Slf4j
@Component
public class RefreshTokenStore {

    private static final String KEY_PREFIX = "easyshell:refresh:";

    private final StringRedisTemplate redisTemplate;

    public RefreshTokenStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void store(Long userId, String tokenId, Duration ttl) {
        if (userId == null || tokenId == null) {
            throw new IllegalArgumentException("userId and tokenId are required");
        }
        redisTemplate.opsForValue().set(key(userId, tokenId), "1", ttl);
    }

    /**
     * @return true when the token was still valid (and is now consumed)
     */
    public boolean consume(Long userId, String tokenId) {
        if (userId == null || tokenId == null) {
            return false;
        }
        return Boolean.TRUE.equals(redisTemplate.delete(key(userId, tokenId)));
    }

    public void revokeAllForUser(Long userId) {
        if (userId == null) {
            return;
        }
        Set<String> keys = redisTemplate.keys(KEY_PREFIX + userId + ":*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
            log.info("Revoked {} refresh tokens for user {}", keys.size(), userId);
        }
    }

    private String key(Long userId, String tokenId) {
        return KEY_PREFIX + userId + ":" + tokenId;
    }

    /** Visible for testing. */
    Set<String> keysFor(Long userId) {
        Set<String> keys = redisTemplate.keys(KEY_PREFIX + userId + ":*");
        return keys == null ? Collections.emptySet() : keys;
    }
}
