package com.easyshell.server.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ClusterRequest {

    @NotBlank(message = "name is required")
    private String name;

    private String description;
}
