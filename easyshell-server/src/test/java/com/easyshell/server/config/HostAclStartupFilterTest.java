package com.easyshell.server.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class HostAclStartupFilterTest {
    @Test
    void rejectsRequestsAndWebSocketUpgradesUntilOpened() throws Exception {
        HostAclStartupBarrier barrier = new HostAclStartupBarrier();
        HostAclStartupFilter filter = new HostAclStartupFilter(barrier);
        for (String path : new String[]{"/api/v1/agents", "/ws/terminal/host"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            request.addHeader("Upgrade", "websocket");
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilter(request, response, chain);

            assertEquals(503, response.getStatus());
            verify(chain, never()).doFilter(request, response);
        }
    }

    @Test
    void forwardsRequestsOnlyAfterOpening() throws Exception {
        HostAclStartupBarrier barrier = new HostAclStartupBarrier();
        HostAclStartupFilter filter = new HostAclStartupFilter(barrier);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/agents");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        barrier.open();
        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }
}
