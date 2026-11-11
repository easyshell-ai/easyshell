package com.easyshell.server.controller;

import com.easyshell.server.config.HostAclStartupBarrier;
import com.easyshell.server.config.HostAclStartupFilter;
import com.easyshell.server.config.SecurityConfig;
import com.easyshell.server.repository.UserRepository;
import com.easyshell.server.security.AgentCredentialGenerationValidator;
import com.easyshell.server.security.JwtAuthenticationFilter;
import com.easyshell.server.security.JwtUtils;
import com.easyshell.server.service.HostPermissionQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(HostPermissionQueryController.class)
@Import({HostPermissionNoStoreFilter.class, HostAclStartupFilter.class, SecurityConfig.class, JwtAuthenticationFilter.class})
class HostPermissionFilterChainTest {
    @Autowired MockMvc mvc;
    @MockBean HostAclStartupBarrier barrier;
    @MockBean HostPermissionQueryService service;
    @MockBean JwtUtils jwtUtils;
    @MockBean UserRepository userRepository;
    @MockBean AgentCredentialGenerationValidator generationValidator;

    @Test
    void barrierAndSecurityRejectionsBothCarryNoStore() throws Exception {
        for (String path : new String[]{"query", "execute-targets"}) {
            var request = path.equals("query") ? post("/api/v1/host-permissions/me/" + path)
                    : get("/api/v1/host-permissions/me/" + path);
            when(barrier.isOpen()).thenReturn(false);
            mvc.perform(request).andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Cache-Control", "no-store"));
            when(barrier.isOpen()).thenReturn(true);
            mvc.perform(request).andExpect(status().is4xxClientError())
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
    }
}
