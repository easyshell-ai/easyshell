package com.easyshell.server.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Blocks every HTTP request, including WebSocket upgrades, until migration commits. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class HostAclStartupFilter extends OncePerRequestFilter {
    private final HostAclStartupBarrier barrier;

    public HostAclStartupFilter(HostAclStartupBarrier barrier) { this.barrier = barrier; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!barrier.isOpen()) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }
        chain.doFilter(request, response);
    }
}
