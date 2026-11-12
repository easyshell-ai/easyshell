package com.easyshell.server.controller;

import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.entity.Agent;
import com.easyshell.server.model.entity.User;
import com.easyshell.server.repository.*;
import com.easyshell.server.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HostControllerDeleteSecurityTest {
    @Mock UserRepository users;
    @Mock AgentRepository agents;
    @Mock AgentTagRepository tags;
    @Mock MetricSnapshotRepository metrics;
    @Mock HostCredentialRepository credentials;
    @Mock HostProvisionService provision;
    @Mock HostIdentityService identities;
    @InjectMocks HostController controller;

    private final Authentication auth = UsernamePasswordAuthenticationToken.authenticated(7L, null,
            List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")));

    @Test
    void bothEndpointsRequireMethodRole() throws Exception {
        for (var method : List.of(HostController.class.getMethod("deleteHost", String.class, Authentication.class),
                HostController.class.getMethod("deleteCredential", Long.class, Authentication.class))) {
            assertEquals("hasRole('SUPER_ADMIN')", method.getAnnotation(PreAuthorize.class).value());
        }
    }

    @Test
    void staleTokenDisabledOrMissingUserCannotDelete() {
        User user = new User();
        user.setRole("admin");
        when(users.findById(7L)).thenReturn(Optional.of(user), Optional.of(user), Optional.empty());
        assertEquals(403, assertThrows(BusinessException.class, () -> controller.deleteHost("a", auth)).getCode());
        user.setRole("super_admin");
        user.setStatus(0);
        assertEquals(403, assertThrows(BusinessException.class, () -> controller.deleteCredential(1L, auth)).getCode());
        assertEquals(403, assertThrows(BusinessException.class, () -> controller.deleteHost("a", auth)).getCode());
        assertEquals(401, assertThrows(BusinessException.class, () -> controller.deleteCredential(1L, null)).getCode());
        verifyNoInteractions(agents, tags, metrics, credentials, provision, identities);
    }

    @Test
    void activeSuperAdminRetiresIdentityAndKeepsCredentials() {
        User user = new User();
        user.setRole("super_admin");
        when(users.findById(7L)).thenReturn(Optional.of(user));
        var identity = new HostIdentityService.Identity("a", 4, true);
        when(identities.lock("a")).thenReturn(identity);
        when(agents.findById("a")).thenReturn(Optional.of(new Agent()));
        controller.deleteHost("a", auth);
        controller.deleteCredential(2L, auth);
        var order = inOrder(identities, agents);
        order.verify(identities).lock("a");
        order.verify(agents).findById("a");
        order.verify(agents).deleteById("a");
        order.verify(identities).retire(identity);
        verifyNoInteractions(credentials);
        verify(provision).deleteById(2L);
    }
}
