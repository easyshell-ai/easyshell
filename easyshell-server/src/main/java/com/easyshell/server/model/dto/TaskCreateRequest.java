package com.easyshell.server.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class TaskCreateRequest {

    @NotBlank(message = "name is required")
    private String name;

    private Long scriptId;

    private String scriptContent;

    private List<String> agentIds;

    private List<Long> clusterIds;

    private List<Long> tagIds;

    private Integer timeoutSeconds = 3600;

    /**
     * Script parameter values for {{variable_name}} substitution.
     * Key: parameter name (without braces), Value: parameter value.
     */
    private Map<String, String> parameters;

    /**
     * 上游已完成人工授权的标记（B1-b 风险门禁下沉后的唯一"已授权"通道）。
     * <p>
     * {@code TaskServiceImpl.createAndDispatch} 是所有派发的唯一咽喉要道，默认对
     * 替换后的脚本跑 {@code CommandRiskEngine.assessScript}，非 LOW 一律拒绝。
     * 只有确实在上游经过人工审批/用户显式配置的路径才允许置 true：
     * {@code AiExecutionService.approveExecution}（管理员审批通过）、
     * {@code AiSchedulerService}（用户自建定时任务）、
     * {@code HostIntelligenceService}（内置只读探测脚本）。
     * 该标记只表达"上游已授权"，不重新实现任何风险判定；新调用方忘记授权时
     * 默认被门禁拦下（fail-closed）。
     */
    private boolean riskCleared;
}
