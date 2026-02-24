package com.easyshell.server.model.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class ClusterAgentRequest {

    @NotEmpty(message = "agentIds is required")
    private List<String> agentIds;
}
