package com.easyshell.server.ai.model.dto;

import lombok.Data;

import java.util.List;

@Data
public class AiChatRequest {
    private String sessionId;
    private String message;
    private String provider;
    private String model;
    private Boolean enableTools;
    private List<String> targetAgentIds;
    private Boolean skipPlanning;
    private Boolean planConfirmed;
}
