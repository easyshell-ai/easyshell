package com.easyshell.server.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ScriptRequest {

    @NotBlank(message = "name is required")
    private String name;

    private String description;

    @NotBlank(message = "content is required")
    private String content;

    private String scriptType = "shell";
    private Boolean isPublic = true;
}
