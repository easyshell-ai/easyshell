package com.easyshell.server.workflow.code;

import com.easyshell.server.workflow.properties.WorkflowCodeWorkerProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkflowCodeWorkerProperties.class)
public class WorkflowCodeWorkerConfiguration {
    @Bean(destroyMethod = "close")
    WorkflowCodeWorkerClient workflowCodeWorkerClient(
            WorkflowCodeWorkerProperties properties,
            @Value("${EASYSHELL_CODE_WORKER_CLIENT_KEYSTORE_PASSWORD:}") String clientPassword,
            @Value("${EASYSHELL_CODE_WORKER_TRUSTSTORE_PASSWORD:}") String trustPassword) {
        return new WorkflowCodeWorkerClient(properties, clientPassword, trustPassword);
    }
}
