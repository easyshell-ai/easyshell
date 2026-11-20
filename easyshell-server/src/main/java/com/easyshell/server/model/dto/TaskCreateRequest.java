package com.easyshell.server.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class TaskCreateRequest {

    @NotBlank(message = "name is required")
    private String name;

    private Long scriptId;

    private String scriptContent;

    private List<String> agentIds;

    private List<Long> clusterIds;

    private List<Long> tagIds;

    private Integer timeoutSeconds = 3600;

    /**
     * Script parameter values for {{variable_name}} substitution.
     * Key: parameter name (without braces), Value: parameter value.
     */
    private Map<String, String> parameters;
}
