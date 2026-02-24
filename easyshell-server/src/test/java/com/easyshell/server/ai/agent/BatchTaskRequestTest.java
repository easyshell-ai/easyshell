package com.easyshell.server.ai.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BatchTaskRequest")
class BatchTaskRequestTest {

    @Test
    void builder_allFields() {
        AgentDefinition agent = new AgentDefinition();
        agent.setName("executor");

        BatchTaskRequest req = BatchTaskRequest.builder()
                .agentDefinition(agent)
                .prompt("do something")
                .hostId("h1")
                .stepIndex(3)
                .timeoutSec(60)
                .build();

        assertThat(req.getAgentDefinition().getName()).isEqualTo("executor");
        assertThat(req.getPrompt()).isEqualTo("do something");
        assertThat(req.getHostId()).isEqualTo("h1");
        assertThat(req.getStepIndex()).isEqualTo(3);
        assertThat(req.getTimeoutSec()).isEqualTo(60);
    }

    @Test
    void noArgsConstructor_defaults() {
        BatchTaskRequest req = new BatchTaskRequest();
        assertThat(req.getAgentDefinition()).isNull();
        assertThat(req.getPrompt()).isNull();
        assertThat(req.getHostId()).isNull();
        assertThat(req.getStepIndex()).isNull();
        assertThat(req.getTimeoutSec()).isZero();
    }
}
