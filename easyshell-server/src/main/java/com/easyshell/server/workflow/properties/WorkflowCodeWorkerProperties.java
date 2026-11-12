package com.easyshell.server.workflow.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment-owned settings; never populate these from workflow/node configuration. */
@Getter
@Setter
@ConfigurationProperties(prefix = "easyshell.workflow.code-worker")
public class WorkflowCodeWorkerProperties {
    private boolean enabled = false;
    /** HTTPS origin only, without credentials, query or fragment. */
    private String endpoint;
    /** Absolute, trusted local PKCS12 file paths, outside task-controlled storage. */
    private String clientKeyStore;
    private String trustStore;
    /** Task phase budget including startup and response; health has a separate bounded 10s phase. */
    private int requestTimeoutMs = 25_000;
}
