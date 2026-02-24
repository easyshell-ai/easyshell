package com.easyshell.server.ai.controller;

import com.easyshell.server.ai.model.dto.AiRiskAssessRequest;
import com.easyshell.server.ai.model.dto.AiRiskRulesSaveRequest;
import com.easyshell.server.ai.model.vo.AiRiskRulesVO;
import com.easyshell.server.ai.model.vo.RiskAssessment;
import com.easyshell.server.ai.risk.CommandRiskEngine;
import com.easyshell.server.common.result.R;
import com.easyshell.server.model.entity.SystemConfig;
import com.easyshell.server.repository.SystemConfigRepository;
import com.easyshell.server.service.AuditLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/ai/risk")
@RequiredArgsConstructor
public class AiRiskController {

    private final SystemConfigRepository systemConfigRepository;
    private final CommandRiskEngine riskEngine;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    @GetMapping("/rules")
    public R<AiRiskRulesVO> getRules() {
        AiRiskRulesVO rules = AiRiskRulesVO.builder()
                .bannedCommands(riskEngine.getCustomBannedPatterns())
                .highCommands(riskEngine.getCustomHighCommands())
                .lowCommands(riskEngine.getCustomLowCommands())
                .build();
        return R.ok(rules);
    }

    @PutMapping("/rules")
    public R<Void> saveRules(@Valid @RequestBody AiRiskRulesSaveRequest request,
                             Authentication auth, HttpServletRequest httpRequest) {
        Long userId = (Long) auth.getPrincipal();

        try {
            if (request.getBannedCommands() != null) {
                saveRuleConfig("ai.risk.banned-commands",
                        objectMapper.writeValueAsString(request.getBannedCommands()),
                        "封禁命令列表（JSON 数组）");
            }
            if (request.getHighCommands() != null) {
                saveRuleConfig("ai.risk.high-commands",
                        objectMapper.writeValueAsString(request.getHighCommands()),
                        "高危命令列表（JSON 数组）");
            }
            if (request.getLowCommands() != null) {
                saveRuleConfig("ai.risk.low-commands",
                        objectMapper.writeValueAsString(request.getLowCommands()),
                        "低风险命令列表（JSON 数组）");
            }
        } catch (Exception e) {
            log.error("Failed to serialize risk rules", e);
            return R.fail(500, "保存失败: " + e.getMessage());
        }

        auditLogService.log(userId, auth.getName(), "UPDATE_RISK_RULES", "ai_risk",
                null, "更新命令风险规则", httpRequest.getRemoteAddr(), "success");

        return R.ok();
    }

    @PostMapping("/assess")
    public R<RiskAssessment> assessScript(@Valid @RequestBody AiRiskAssessRequest request) {
        RiskAssessment assessment = riskEngine.assessScript(request.getScriptContent());
        return R.ok(assessment);
    }

    private void saveRuleConfig(String key, String value, String description) {
        SystemConfig config = systemConfigRepository.findByConfigKey(key).orElse(null);
        if (config != null) {
            config.setConfigValue(value);
        } else {
            config = new SystemConfig();
            config.setConfigKey(key);
            config.setConfigValue(value);
            config.setDescription(description);
            config.setConfigGroup("ai-risk");
        }
        systemConfigRepository.save(config);
    }
}
