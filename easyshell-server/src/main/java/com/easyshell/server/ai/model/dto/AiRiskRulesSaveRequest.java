package com.easyshell.server.ai.model.dto;

import lombok.Data;

import java.util.List;

@Data
public class AiRiskRulesSaveRequest {
    private List<String> bannedCommands;
    private List<String> highCommands;
    private List<String> lowCommands;
}
