package com.easyshell.server.ai.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchTaskRequest {

    private AgentDefinition agentDefinition;
    private String prompt;
    private String hostId;
    private Integer stepIndex;
    private int timeoutSec;
}
