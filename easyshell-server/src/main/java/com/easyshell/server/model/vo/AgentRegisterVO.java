package com.easyshell.server.model.vo;

import com.easyshell.server.model.entity.Agent;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentRegisterVO {

    private Agent agent;
    /** Signed credential the agent must present on every subsequent agent API call and WS handshake. */
    private String agentToken;
}
