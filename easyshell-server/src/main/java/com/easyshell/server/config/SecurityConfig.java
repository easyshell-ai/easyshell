package com.easyshell.server.config;

import com.easyshell.server.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .headers(headers -> headers.frameOptions(HeadersConfigurer.FrameOptionsConfig::deny))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/api/v1/auth/login",
                    "/api/v1/auth/refresh",
                    "/api/health",
                    "/error"
                ).permitAll()
                // Registration is the bootstrap endpoint: the controller itself requires an
                // agent token for the same id, an administrator token, or the registration
                // secret, so it cannot be used to register an arbitrary ghost host.
                .requestMatchers("/api/v1/agent/register").permitAll()
                // Agent credentials: token subject must match the agentId used by the endpoint.
                .requestMatchers("/api/v1/agent/**").hasRole("AGENT")
                // Health probes must stay reachable; every other actuator endpoint is admin-only.
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/actuator/**").hasRole("SUPER_ADMIN")
                // WebSocket handshakes are authenticated in WebSocketAuthInterceptor
                // (browsers cannot send an Authorization header, so the filter chain
                // cannot do it; the interceptor rejects before any handler runs).
                .requestMatchers("/ws/**").permitAll()
                // Webhooks are authenticated by provider signature verification in code.
                .requestMatchers("/api/v1/webhook/**").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
