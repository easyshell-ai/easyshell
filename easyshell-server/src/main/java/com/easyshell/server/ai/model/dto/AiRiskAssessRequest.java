package com.easyshell.server.ai.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AiRiskAssessRequest {
    @NotBlank
    private String scriptContent;
}
