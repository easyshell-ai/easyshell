package com.easyshell.server.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class FileRenameRequest {
    @NotBlank(message = "Old path is required")
    private String oldPath;

    @NotBlank(message = "New path is required")
    private String newPath;
}
