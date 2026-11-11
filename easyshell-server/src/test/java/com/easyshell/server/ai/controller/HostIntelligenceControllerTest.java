package com.easyshell.server.ai.controller;

import com.easyshell.server.ai.model.entity.HostSoftwareInventory;
import com.easyshell.server.ai.service.HostIntelligenceService;
import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.service.HostAclService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HostIntelligenceControllerTest {
    @Mock HostIntelligenceService service;
    @Mock HostAclService acl;
    @InjectMocks HostIntelligenceController controller;

    private final Authentication auth = UsernamePasswordAuthenticationToken.authenticated(7L, null, List.of());

    private void read(String endpoint, Authentication authentication) {
        switch (endpoint) {
            case "software" -> controller.getSoftware("host", authentication);
            case "docker" -> controller.getDockerContainers("host", authentication);
            case "inventory" -> controller.getInventory("host", authentication);
            default -> throw new IllegalArgumentException(endpoint);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"software", "docker", "inventory"})
    void readsRequireViewAndNeverReadOnDenial(String endpoint) {
        assertEquals(403, assertThrows(BusinessException.class, () -> read(endpoint, auth)).getCode());
        verify(acl).allowed(7L, "host", HostAclService.Permission.VIEW);
        verifyNoInteractions(service);
    }

    @Test
    void authorizedReadsPreserveResults() {
        var items = List.of(new HostSoftwareInventory());
        when(acl.allowed(7L, "host", HostAclService.Permission.VIEW)).thenReturn(true);
        when(service.getSoftwareList("host")).thenReturn(items);
        when(service.getDockerContainers("host")).thenReturn(items);
        when(service.getAllInventory("host")).thenReturn(items);
        assertSame(items, controller.getSoftware("host", auth).getData());
        assertSame(items, controller.getDockerContainers("host", auth).getData());
        assertSame(items, controller.getInventory("host", auth).getData());
    }

    @Test
    void detectRequiresExecuteAndPassesAuthenticatedUser() {
        assertEquals(403, assertThrows(BusinessException.class,
                () -> controller.triggerDetection("host", auth)).getCode());
        verifyNoInteractions(service);
        when(acl.allowed(7L, "host", HostAclService.Permission.EXECUTE)).thenReturn(true);
        when(service.triggerDetection("host", 7L)).thenReturn("task");
        assertEquals("task", controller.triggerDetection("host", auth).getData());
        verify(service).triggerDetection("host", 7L);
    }

    @Test
    void parseIsDeniedEvenWithExecuteAndNeverChangesInventoryOrTags() {
        when(acl.allowed(7L, "host", HostAclService.Permission.EXECUTE)).thenReturn(true);
        BusinessException error = assertThrows(BusinessException.class,
                () -> controller.parseDetection("host", "forged-task", auth));
        assertEquals(403, error.getCode());
        assertTrue(error.getMessage().contains("provenance"));
        verifyNoInteractions(service);
    }

    @Test
    void parseWithoutExecuteIsDeniedWithoutSideEffects() {
        assertEquals(403, assertThrows(BusinessException.class,
                () -> controller.parseDetection("host", "task", auth)).getCode());
        verify(acl).allowed(7L, "host", HostAclService.Permission.EXECUTE);
        verifyNoInteractions(service);
    }

    @Test
    void invalidAuthenticationNeverReachesAclOrService() {
        Authentication forged = UsernamePasswordAuthenticationToken.authenticated("7", null, List.of());
        Authentication unauthenticated = UsernamePasswordAuthenticationToken.unauthenticated(7L, null);
        assertEquals(401, assertThrows(BusinessException.class, () -> controller.getSoftware("host", forged)).getCode());
        assertEquals(401, assertThrows(BusinessException.class, () -> controller.triggerDetection("host", unauthenticated)).getCode());
        assertEquals(401, assertThrows(BusinessException.class, () -> controller.parseDetection("host", "task", null)).getCode());
        verifyNoInteractions(acl, service);
    }

    @Test
    void aclDatabaseFailureNeverReachesService() {
        when(acl.allowed(7L, "host", HostAclService.Permission.VIEW))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));
        assertThrows(DataAccessResourceFailureException.class, () -> controller.getInventory("host", auth));
        verifyNoInteractions(service);
    }
}
