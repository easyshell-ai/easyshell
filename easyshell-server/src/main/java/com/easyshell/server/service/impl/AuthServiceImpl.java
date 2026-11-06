package com.easyshell.server.service.impl;

import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.dto.LoginRequest;
import com.easyshell.server.model.dto.LoginResponse;
import com.easyshell.server.model.entity.User;
import com.easyshell.server.model.vo.UserVO;
import com.easyshell.server.repository.UserRepository;
import com.easyshell.server.security.JwtUtils;
import com.easyshell.server.security.RefreshTokenStore;
import com.easyshell.server.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final int MAX_FAILED_ATTEMPTS = 10;
    private static final long ATTEMPT_WINDOW_MS = 5 * 60 * 1000L;
    private static final long RETRY_AFTER_MS = 60 * 1000L;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    private final RefreshTokenStore refreshTokenStore;

    /** In-memory login throttle keyed by "username|ip". Single-instance deployment. */
    private final ConcurrentHashMap<String, AttemptWindow> attempts = new ConcurrentHashMap<>();

    @Override
    @Transactional
    public LoginResponse login(LoginRequest request, String sourceIp) {
        String key = request.getUsername() + "|" + (sourceIp == null ? "unknown" : sourceIp);
        if (isThrottled(key)) {
            throw new BusinessException(429, "Too many failed login attempts, please try again later");
        }

        User user = userRepository.findByUsername(request.getUsername()).orElse(null);

        // Always run a BCrypt comparison so that a missing user costs the same as a wrong
        // password; otherwise response timing reveals which usernames exist.
        boolean matches = passwordEncoder.matches(request.getPassword(),
                user != null ? user.getPassword() : dummyHash());

        if (user == null || !matches) {
            registerFailure(key);
            throw new BusinessException(401, "Invalid username or password");
        }

        if (user.getStatus() != 1) {
            registerFailure(key);
            throw new BusinessException(403, "Account is disabled");
        }

        attempts.remove(key);
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);

        return issueTokens(user);
    }

    private volatile String dummyPasswordHash;

    /** BCrypt hash of a throwaway password, used to equalise login timing. */
    private String dummyHash() {
        if (dummyPasswordHash == null) {
            dummyPasswordHash = passwordEncoder.encode("timing-equalisation-placeholder");
        }
        return dummyPasswordHash;
    }

    private LoginResponse issueTokens(User user) {
        String accessToken = jwtUtils.generateAccessToken(user.getId(), user.getUsername(), user.getRole());
        String refreshToken = jwtUtils.generateRefreshToken(user.getId());
        refreshTokenStore.store(user.getId(), jwtUtils.getTokenId(refreshToken),
                Duration.ofMillis(jwtUtils.getRefreshExpire()));
        return LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .expiresIn(jwtUtils.getJwtExpire() / 1000)
                .build();
    }

    private boolean isThrottled(String key) {
        AttemptWindow window = attempts.get(key);
        if (window == null) {
            return false;
        }
        synchronized (window) {
            long now = System.currentTimeMillis();
            if (now - window.windowStart > ATTEMPT_WINDOW_MS) {
                attempts.remove(key, window);
                return false;
            }
            if (window.blockedUntil > now) {
                return true;
            }
            return window.failures.get() >= MAX_FAILED_ATTEMPTS;
        }
    }

    private void registerFailure(String key) {
        long now = System.currentTimeMillis();
        attempts.compute(key, (k, existing) -> {
            AttemptWindow window = existing;
            if (window == null || now - window.windowStart > ATTEMPT_WINDOW_MS) {
                window = new AttemptWindow(now);
            }
            synchronized (window) {
                window.failures.incrementAndGet();
                if (window.failures.get() >= MAX_FAILED_ATTEMPTS) {
                    window.blockedUntil = now + RETRY_AFTER_MS;
                }
            }
            return window;
        });
    }

    private static final class AttemptWindow {
        private final long windowStart;
        private final AtomicInteger failures = new AtomicInteger();
        private volatile long blockedUntil;

        private AttemptWindow(long windowStart) {
            this.windowStart = windowStart;
        }
    }

    @Override
    public LoginResponse refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BusinessException(401, "Invalid refresh token");
        }
        if (!jwtUtils.validateToken(refreshToken)) {
            throw new BusinessException(401, "Invalid refresh token");
        }

        String type;
        Long userId;
        String tokenId;
        try {
            type = jwtUtils.getTokenType(refreshToken);
            userId = jwtUtils.getUserIdFromToken(refreshToken);
            tokenId = jwtUtils.getTokenId(refreshToken);
        } catch (Exception e) {
            throw new BusinessException(401, "Invalid refresh token");
        }

        // Only dedicated refresh tokens may be used here - an access token must never work.
        if (!JwtUtils.TOKEN_TYPE_REFRESH.equals(type) || userId == null || tokenId == null) {
            throw new BusinessException(401, "Invalid refresh token");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(401, "User not found"));

        if (user.getStatus() != 1) {
            refreshTokenStore.revokeAllForUser(userId);
            throw new BusinessException(403, "Account is disabled");
        }

        // Rotation + replay detection: the presented token must still be registered.
        if (!refreshTokenStore.consume(userId, tokenId)) {
            log.warn("Refresh token replay or revocation detected for user {}", userId);
            refreshTokenStore.revokeAllForUser(userId);
            throw new BusinessException(401, "Refresh token is no longer valid");
        }

        return issueTokens(user);
    }

    @Override
    public UserVO getCurrentUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(404, "User not found"));

        return UserVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .role(user.getRole())
                .status(user.getStatus())
                .lastLoginAt(user.getLastLoginAt())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
