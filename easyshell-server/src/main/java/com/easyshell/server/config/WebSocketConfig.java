package com.easyshell.server.config;

import com.easyshell.server.websocket.AgentWebSocketHandler;
import com.easyshell.server.websocket.TaskLogWebSocketHandler;
import com.easyshell.server.websocket.TerminalWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final TaskLogWebSocketHandler taskLogHandler;
    private final AgentWebSocketHandler agentHandler;
    private final TerminalWebSocketHandler terminalHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(taskLogHandler, "/ws/task/log/*").setAllowedOrigins("*");
        registry.addHandler(agentHandler, "/ws/agent/*").setAllowedOrigins("*");
        registry.addHandler(terminalHandler, "/ws/terminal/*").setAllowedOrigins("*");
    }

    private static final int ONE_MB = 1024 * 1024;

    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(ONE_MB);
        container.setMaxBinaryMessageBufferSize(ONE_MB);
        container.setMaxSessionIdleTimeout(0L);
        return container;
    }
}
