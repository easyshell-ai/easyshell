package com.easyshell.server.config;

import com.easyshell.server.websocket.AgentWebSocketHandler;
import com.easyshell.server.websocket.TaskLogWebSocketHandler;
import com.easyshell.server.websocket.TerminalWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final TaskLogWebSocketHandler taskLogHandler;
    private final AgentWebSocketHandler agentHandler;
    private final TerminalWebSocketHandler terminalHandler;
    private final WebSocketAuthInterceptor webSocketAuthInterceptor;

    /**
     * Explicit origin whitelist. Empty (default) means "same origin only", which is what
     * the SPA needs; agents do not send an Origin header at all.
     */
    @Value("${easyshell.websocket.allowed-origins:}")
    private String allowedOrigins;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        registry.addHandler(taskLogHandler, "/ws/task/log/*")
                .setAllowedOrigins(origins.toArray(new String[0]))
                .addInterceptors(webSocketAuthInterceptor);
        registry.addHandler(agentHandler, "/ws/agent/*")
                .setAllowedOrigins(origins.toArray(new String[0]))
                .addInterceptors(webSocketAuthInterceptor);
        registry.addHandler(terminalHandler, "/ws/terminal/*")
                .setAllowedOrigins(origins.toArray(new String[0]))
                .addInterceptors(webSocketAuthInterceptor);
    }

    private static final int ONE_MB = 1024 * 1024;

    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(ONE_MB);
        container.setMaxBinaryMessageBufferSize(ONE_MB);
        // Reap idle sessions instead of keeping them forever (L9)
        container.setMaxSessionIdleTimeout(30 * 60 * 1000L);
        return container;
    }
}
