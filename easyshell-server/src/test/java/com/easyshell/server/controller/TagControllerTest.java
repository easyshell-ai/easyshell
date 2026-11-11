package com.easyshell.server.controller;

import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.dto.TagRequest;
import com.easyshell.server.model.entity.User;
import com.easyshell.server.repository.UserRepository;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.service.HostAclService;
import com.easyshell.server.service.TagService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TagControllerTest {
    private final TagService tags = mock(TagService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final HostAclService acl = mock(HostAclService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final Authentication auth = new UsernamePasswordAuthenticationToken(1L, "", List.of());
    private TagController controller;

    @BeforeEach
    void setup() {
        controller = new TagController(tags, audit, acl, users);
    }

    private void user(String role, int status) {
        User user = new User();
        user.setRole(role);
        user.setStatus(status);
        when(users.findById(1L)).thenReturn(Optional.of(user));
    }

    private void forbidden(Runnable action) {
        assertEquals(403, assertThrows(BusinessException.class, action::run).getCode());
    }

    @Test
    void readsRequireView() {
        forbidden(() -> controller.getAgentTags("hidden", auth));
        verify(tags, never()).getAgentTags(anyString());
        when(acl.allowed(1L, "visible", HostAclService.Permission.VIEW)).thenReturn(true);
        controller.getAgentTags("visible", auth);
        verify(tags).getAgentTags("visible");
    }

    @Test
    void viewDoesNotGrantManagementAndDatabaseRoleIsAuthoritative() {
        when(acl.allowed(anyLong(), anyString(), eq(HostAclService.Permission.VIEW))).thenReturn(true);
        user("viewer", 1);
        forbidden(() -> controller.addTagToAgent(2L, "visible", auth, request));
        forbidden(() -> controller.removeTagFromAgent(2L, "visible", auth, request));
        forbidden(() -> controller.create(new TagRequest(), auth, request));
        user("admin", 0);
        forbidden(() -> controller.addTagToAgent(2L, "visible", auth, request));
        verifyNoInteractions(tags);
    }

    @Test
    void adminMustHaveViewOfEachHostAndGlobalChangesFailClosed() {
        user("admin", 1);
        when(acl.allowed(1L, "visible", HostAclService.Permission.VIEW)).thenReturn(true);
        controller.addTagToAgent(2L, "visible", auth, request);
        controller.removeTagFromAgent(2L, "visible", auth, request);
        forbidden(() -> controller.addTagToAgent(2L, "hidden", auth, request));
        forbidden(() -> controller.removeTagFromAgent(2L, "hidden", auth, request));
        forbidden(() -> controller.update(2L, new TagRequest(), auth, request));
        forbidden(() -> controller.delete(2L, auth, request));
        verify(tags).addTagToAgent(2L, "visible");
        verify(tags).removeTagFromAgent(2L, "visible");
        verify(tags, never()).update(anyLong(), any());
        verify(tags, never()).delete(anyLong());
    }
}
