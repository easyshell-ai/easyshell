package com.easyshell.server.ai.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiRiskRulesVO {
    private List<String> bannedCommands;
    private List<String> highCommands;
    private List<String> lowCommands;
    private List<String> defaultBannedCommands;
    private List<String> defaultHighCommands;
    private List<String> defaultLowCommands;
}
