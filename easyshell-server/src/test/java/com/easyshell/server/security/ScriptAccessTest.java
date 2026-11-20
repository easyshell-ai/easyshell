package com.easyshell.server.security;

import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.entity.Script;
import com.easyshell.server.model.entity.User;
import com.easyshell.server.repository.ScriptRepository;
import com.easyshell.server.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScriptAccessTest {
    private final ScriptRepository scripts = mock(ScriptRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final ScriptAccess access = new ScriptAccess(scripts, users);

    @Test
    void currentDatabaseIdentityControlsBothNormalAndLockedRead() {
        User user = new User(); user.setId(1L); user.setRole("operator");
        Script script = new Script(); script.setId(7L); script.setCreatedBy(2L); script.setIsPublic(false);
        when(users.findById(1L)).thenReturn(Optional.of(user));
        when(scripts.findById(7L)).thenReturn(Optional.of(script));
        when(scripts.findByIdForSnapshot(7L)).thenReturn(Optional.of(script));
        assertNotFound(() -> access.requireRead(1L, 7L));
        assertNotFound(() -> access.requireReadForSnapshot(1L, 7L));
        script.setIsPublic(true);
        assertThat(access.requireRead(1L, 7L)).isSameAs(script);
        script.setIsPublic(false); script.setCreatedBy(1L);
        assertThat(access.requireReadForSnapshot(1L, 7L)).isSameAs(script);
        script.setCreatedBy(2L); user.setRole("admin");
        assertThat(access.requireRead(1L, 7L)).isSameAs(script);
        user.setStatus(0);
        assertNotFound(() -> access.requireRead(1L, 7L));
        assertNotFound(() -> access.requireReadForSnapshot(1L, 7L));
        assertNotFound(() -> access.requireRead(null, 7L));
        user.setStatus(1);
        when(scripts.findById(8L)).thenReturn(Optional.empty());
        assertNotFound(() -> access.requireRead(1L, 8L));
    }

    private static void assertNotFound(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(404));
    }
}
