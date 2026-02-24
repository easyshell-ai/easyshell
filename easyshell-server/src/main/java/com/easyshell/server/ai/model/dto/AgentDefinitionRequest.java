package com.easyshell.server.ai.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AgentDefinitionRequest {

    @NotBlank
    @Size(max = 50)
    private String name;

    @Size(max = 100)
    private String displayName;

    @NotBlank
    @Size(max = 20)
    private String mode;

    @NotBlank
    private String permissions;

    @Size(max = 50)
    private String modelProvider;

    @Size(max = 100)
    private String modelName;

    @NotBlank
    private String systemPrompt;

    private Integer maxIterations = 5;

    private Boolean enabled = true;

    private String description;
}
