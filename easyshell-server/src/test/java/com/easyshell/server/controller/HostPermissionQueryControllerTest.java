package com.easyshell.server.controller;

import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.common.exception.GlobalExceptionHandler;
import com.easyshell.server.service.HostPermissionQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class HostPermissionQueryControllerTest {
    @Test
    void executeTargetsUsesAuthenticatedPrincipalAndSetsNoStoreOnFailures() {
        var service = mock(HostPermissionQueryService.class);
        var controller = new HostPermissionQueryController(service);
        var response = new MockHttpServletResponse();
        var request = new MockHttpServletRequest();
        var auth = new UsernamePasswordAuthenticationToken(9L, "", List.of());
        when(service.executeTargets(9L, null, 100)).thenReturn(new HostPermissionQueryService.ExecuteTargetsPage(List.of("a"), null));
        var result = controller.executeTargets(null, request, auth, response);
        var mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        assertTrue(mapper.valueToTree(result).get("data").has("nextCursor"));
        assertTrue(mapper.valueToTree(result).get("data").get("nextCursor").isNull());
        verify(service).executeTargets(9L, null, 100);
        assertEquals("no-store", response.getHeader("Cache-Control"));
        var invalidResponse = new MockHttpServletResponse();
        assertEquals(403, assertThrows(BusinessException.class,
                () -> controller.executeTargets(null, request, null, invalidResponse)).getCode());
        assertEquals("no-store", invalidResponse.getHeader("Cache-Control"));
        when(service.executeTargets(9L, null, 100)).thenThrow(new BusinessException(503, "unavailable"));
        var errorResponse = new MockHttpServletResponse();
        assertEquals(503, assertThrows(BusinessException.class,
                () -> controller.executeTargets(null, request, auth, errorResponse)).getCode());
        assertEquals("no-store", errorResponse.getHeader("Cache-Control"));
    }

    @Test
    void executeTargetsRejectsMalformedOverflowAndRepeatedLimitWithNoStore() throws Exception {
        var service = mock(HostPermissionQueryService.class);
        var mvc = standaloneSetup(new HostPermissionQueryController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        var auth = new UsernamePasswordAuthenticationToken(9L, "", List.of());
        for (String[] limits : List.of(new String[]{"abc"}, new String[]{"1,2"}, new String[]{"2147483648"},
                new String[]{"-2147483649"}, new String[]{"1", "2"}, new String[]{"1", "1"})) {
            mvc.perform(get("/api/v1/host-permissions/me/execute-targets")
                            .principal(auth).param("limit", limits))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.code").value(400));
        }
        verifyNoInteractions(service);
    }

    @Test
    void executeTargetsBindsDefaultAndValidLimit() throws Exception {
        var service = mock(HostPermissionQueryService.class);
        when(service.executeTargets(eq(9L), isNull(), anyInt()))
                .thenReturn(new HostPermissionQueryService.ExecuteTargetsPage(List.of(), null));
        var mvc = standaloneSetup(new HostPermissionQueryController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        var auth = new UsernamePasswordAuthenticationToken(9L, "", List.of());
        mvc.perform(get("/api/v1/host-permissions/me/execute-targets").principal(auth))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/api/v1/host-permissions/me/execute-targets").principal(auth).param("limit", "25"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(service).executeTargets(9L, null, 100);
        verify(service).executeTargets(9L, null, 25);
    }

    @Test
    void noStoreFilterCoversBothEndpointsBeforeSecurityErrors() throws Exception {
        var filter = new HostPermissionNoStoreFilter();
        for (String path : List.of("query", "execute-targets")) {
            var request = new MockHttpServletRequest("GET", "/ctx/api/v1/host-permissions/me/" + path);
            request.setContextPath("/ctx");
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).sendError(403));
            assertEquals("no-store", response.getHeader("Cache-Control"));
        }
    }

    @Test
    void acceptsOnlyBoundedAgentIdsAndUsesPrincipal() throws Exception {
        var service = mock(HostPermissionQueryService.class);
        var controller = new HostPermissionQueryController(service);
        var auth = new UsernamePasswordAuthenticationToken(9L, "", List.of());
        var response = new MockHttpServletResponse();
        var json = new ObjectMapper();
        controller.query(json.readTree("{\"agentIds\":[\"a\"]}"), auth, response);
        verify(service).query(9L, List.of("a"));
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertEquals(400, assertThrows(BusinessException.class, () -> controller.query(
                json.readTree("{\"agentIds\":[],\"userId\":1}"), auth, response)).getCode());
        assertEquals(400, assertThrows(BusinessException.class, () -> controller.query(
                json.readTree("{\"agentIds\":[],\"role\":\"admin\"}"), auth, response)).getCode());
        var tooMany = json.createObjectNode();
        var ids = tooMany.putArray("agentIds");
        for (int i = 0; i < 101; i++) ids.add("a");
        assertEquals(400, assertThrows(BusinessException.class, () -> controller.query(tooMany, auth, response)).getCode());
        assertEquals(403, assertThrows(BusinessException.class, () -> controller.query(
                json.readTree("{\"agentIds\":[]}"), null, response)).getCode());
        verifyNoMoreInteractions(service);
    }
}
