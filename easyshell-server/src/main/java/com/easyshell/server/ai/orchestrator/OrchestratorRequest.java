package com.easyshell.server.ai.orchestrator;

import lombok.Data;
import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.List;

@Data
public class OrchestratorRequest {
    private String sessionId;
    private String userMessage;
    private Long userId;
    private String sourceIp;
    private String provider;
    private String model;
    private boolean enableTools = true;
    private List<String> targetAgentIds;
    private List<Message> history = new ArrayList<>();

    /** 最大迭代次数，由调用方从 AgenticConfigService 读取并设置 */
    private int maxIterations;
    /** 最大连续错误次数，由调用方从 AgenticConfigService 读取并设置 */
    private int maxConsecutiveErrors;
    /** 单次迭代最大工具调用次数 */
    private int maxToolCalls;

    private String responseContent;

    /** Pre-generated execution plan (set by planning phase) */
    private ExecutionPlan executionPlan;
    /** Whether the user has confirmed the plan */
    private boolean planConfirmed = false;
    /** Skip planning phase — go directly to agentic loop */
    private boolean skipPlanning = false;
}
