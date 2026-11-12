package com.easyshell.server.service.impl;

import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.dto.LoginRequest;
import com.easyshell.server.model.dto.PasswordResetRequest;
import com.easyshell.server.model.dto.UserUpdateRequest;
import com.easyshell.server.model.entity.User;
import com.easyshell.server.repository.UserRepository;
import com.easyshell.server.security.JwtUtils;
import com.easyshell.server.security.RefreshTokenStore;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserWriteLockTest {
    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);
    private final RefreshTokenStore tokens = mock(RefreshTokenStore.class);
    private final JwtUtils jwt = mock(JwtUtils.class);
    private final UserServiceImpl userService = new UserServiceImpl(users, passwords, tokens);
    private final AuthServiceImpl auth = new AuthServiceImpl(users, passwords, jwt, tokens);

    private User user() {
        User user = new User();
        user.setId(1L);
        user.setUsername("alice");
        user.setPassword("new-hash");
        user.setRole("viewer");
        user.setStatus(1);
        return user;
    }

    @Test
    void updateAndPasswordResetUseLockedCurrentUser() {
        User user = user();
        user.setStatus(0);
        when(users.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(passwords.encode("new-password")).thenReturn("encoded");
        UserUpdateRequest update = new UserUpdateRequest();
        update.setEmail("alice@example.test");
        userService.update(1L, update);
        PasswordResetRequest reset = new PasswordResetRequest();
        reset.setNewPassword("new-password");
        userService.resetPassword(1L, reset);
        assertEquals("viewer", user.getRole());
        assertEquals(0, user.getStatus());
        verify(users, times(2)).findByIdForUpdate(1L);
        verify(users, never()).findById(1L);
    }

    @Test
    void deleteUsesLockedUser() {
        User user = user();
        when(users.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        userService.delete(1L);
        verify(users).delete(user);
        verify(users, never()).findById(1L);
    }

    @Test
    void loginChecksLockedPasswordAndStatusBeforeWritingOrIssuingTokens() {
        User user = user();
        when(users.findByUsernameForUpdate("alice")).thenReturn(Optional.of(user));
        LoginRequest request = new LoginRequest();
        request.setUsername("alice");
        request.setPassword("old-password");
        when(passwords.matches("old-password", "new-hash")).thenReturn(false);
        assertThrows(BusinessException.class, () -> auth.login(request, "ip"));
        when(passwords.matches("old-password", "new-hash")).thenReturn(true);
        user.setStatus(0);
        assertThrows(BusinessException.class, () -> auth.login(request, "ip"));
        verify(users, times(2)).findByUsernameForUpdate("alice");
        verify(users, never()).findByUsername(anyString());
        verify(users, never()).save(any());
        verifyNoInteractions(jwt, tokens);
    }

    @Test
    void successfulLoginUsesLockedRoleAndPreservesStatus() {
        User user = user();
        when(users.findByUsernameForUpdate("alice")).thenReturn(Optional.of(user));
        when(passwords.matches("password", "new-hash")).thenReturn(true);
        when(jwt.generateRefreshToken(1L)).thenReturn("refresh");
        when(jwt.getTokenId("refresh")).thenReturn("token-id");
        LoginRequest request = new LoginRequest();
        request.setUsername("alice");
        request.setPassword("password");
        auth.login(request, "ip");
        verify(jwt).generateAccessToken(1L, "alice", "viewer");
        verify(users).save(user);
        assertEquals("viewer", user.getRole());
        assertEquals(1, user.getStatus());
        assertNotNull(user.getLastLoginAt());
    }
}
