package com.easyshell.server.controller;

import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.repository.HostAclRepository;
import com.easyshell.server.service.HostAclService;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HostAclControllerTest {
    @Test
    void grantAndRevokeForwardGenerationAndPreserveServiceErrors() {
        var service = mock(HostAclService.class);
        var controller = new HostAclController(service, mock(HostAclRepository.class));
        var auth = new UsernamePasswordAuthenticationToken(1L, "", List.of());
        var request = new HostAclController.GrantRequest("ROLE", "viewer", HostAclService.Permission.VIEW, 7L);
        assertEquals(200, controller.grant("a", request, auth).getCode());
        assertEquals(200, controller.revoke("a", request, auth).getCode());
        verify(service).change(1L, "a", "ROLE", "viewer", HostAclService.Permission.VIEW, true, 7L);
        verify(service).change(1L, "a", "ROLE", "viewer", HostAclService.Permission.VIEW, false, 7L);

        var missing = new HostAclController.GrantRequest("ROLE", "viewer", HostAclService.Permission.VIEW, null);
        doThrow(new BusinessException(400, "Expected generation required"))
                .when(service).change(1L, "a", "ROLE", "viewer", HostAclService.Permission.VIEW, true, null);
        doThrow(new BusinessException(400, "Expected generation required"))
                .when(service).change(1L, "a", "ROLE", "viewer", HostAclService.Permission.VIEW, false, null);
        assertEquals(400, assertThrows(BusinessException.class, () -> controller.grant("a", missing, auth)).getCode());
        assertEquals(400, assertThrows(BusinessException.class, () -> controller.revoke("a", missing, auth)).getCode());

        doThrow(new BusinessException(409, "Host generation changed"))
                .when(service).change(1L, "a", "ROLE", "viewer", HostAclService.Permission.VIEW, true, 7L);
        doThrow(new BusinessException(409, "Host generation changed"))
                .when(service).change(1L, "a", "ROLE", "viewer", HostAclService.Permission.VIEW, false, 7L);
        assertEquals(409, assertThrows(BusinessException.class, () -> controller.grant("a", request, auth)).getCode());
        assertEquals(409, assertThrows(BusinessException.class, () -> controller.revoke("a", request, auth)).getCode());
    }

    @Test
    void catalogValidatesPaginationAndPrincipalAndIsRoleRestricted() {
        var repository = mock(HostAclRepository.class);
        var controller = new HostAclController(mock(HostAclService.class), repository);
        var auth = new UsernamePasswordAuthenticationToken(1L, "", List.of());
        var host = new HostAclRepository.CatalogHost("a", "host", 2);
        when(repository.catalog(1, 10, 20)).thenReturn(List.of(host));
        assertEquals(List.of(host), controller.catalog(2, 10, auth).getData());
        verify(repository).catalog(1, 10, 20);
        assertEquals(400, assertThrows(BusinessException.class, () -> controller.catalog(-1, 10, auth)).getCode());
        assertEquals(400, assertThrows(BusinessException.class, () -> controller.catalog(0, 101, auth)).getCode());
        assertEquals(403, assertThrows(BusinessException.class, () -> controller.catalog(0, 10, null)).getCode());
        assertEquals("hasAnyRole('ADMIN','SUPER_ADMIN')", HostAclController.class.getAnnotation(PreAuthorize.class).value());
        verifyNoMoreInteractions(repository);
    }

    @Test
    void catalogContractExposesOnlyMinimalFieldsAndHasLiteralRoute() throws Exception {
        var mapping = HostAclController.class.getMethod("catalog", int.class, int.class,
                org.springframework.security.core.Authentication.class).getAnnotation(GetMapping.class);
        assertArrayEquals(new String[]{"/catalog"}, mapping.value());
        var json = new ObjectMapper().valueToTree(new HostAclRepository.CatalogHost("a", "host", 2));
        assertEquals(3, json.size());
        assertEquals("a", json.get("agentId").asText());
        assertEquals("host", json.get("hostname").asText());
        assertEquals(2, json.get("generation").asLong());
    }
}
