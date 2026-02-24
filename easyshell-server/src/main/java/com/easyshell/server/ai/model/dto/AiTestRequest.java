package com.easyshell.server.ai.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AiTestRequest {

    @NotBlank(message = "provider 不能为空")
    private String provider;

    private String apiKey;

    private String baseUrl;

    private String model;
}
