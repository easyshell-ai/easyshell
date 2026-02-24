package com.easyshell.server.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EnableJpaAuditing
@EnableJpaRepositories(basePackages = {
        "com.easyshell.server.repository",
        "com.easyshell.server.ai.repository",
        "com.easyshell.server.ai.agent"
})
public class JpaConfig {
}
