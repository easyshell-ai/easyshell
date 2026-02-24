package com.easyshell.server.ai.model.vo;

import com.easyshell.server.ai.risk.RiskLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommandRisk {
    private String command;
    private RiskLevel level;
    private String reason;
}
