package com.easyshell.server.workflow.node;

import com.easyshell.server.ai.security.AiQuotaService;
import com.easyshell.server.ai.security.SensitiveDataFilter;
import com.easyshell.server.ai.service.ChatModelFactory;
import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.workflow.engine.NodeCancelledException;
import com.easyshell.server.workflow.engine.NodeContext;
import com.easyshell.server.workflow.engine.NodeExecutor;
import com.easyshell.server.workflow.engine.NodeFailedException;
import com.easyshell.server.workflow.engine.NodeOutcome;
import com.easyshell.server.workflow.expression.ExpressionEvaluator;
import com.easyshell.server.workflow.expression.ExpressionException;
import com.easyshell.server.workflow.graph.ValidationIssue;
import com.easyshell.server.workflow.model.NodeType;
import com.easyshell.server.workflow.model.ValueType;
import com.easyshell.server.workflow.model.WorkflowDefinition;
import com.easyshell.server.workflow.model.WorkflowNode;
import com.easyshell.server.workflow.properties.WorkflowProperties;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * ai-analyze 节点执行器（工作流 2.0 §2.1）。
 *
 * <p><b>安全边界</b>：本节点只做"读上游输出 → LLM 分析 → 结论文本"，不注入任何
 * 任务派发或执行授权依赖，不产生任何执行能力。系统提示明确禁止输出可执行命令；
 * 输入与输出都过 {@link SensitiveDataFilter}，调用计入 {@link AiQuotaService} 配额。</p>
 *
 * <p><b>线程隔离</b>：LLM 调用跑在 {@code workflowAiPool}（独立有界池），杜绝慢模型
 * 占死引擎线程。等待上限 {@code min(node.timeoutSec, 180s)}，超时取消 future；
 * 队列满/模型不可用/配额超限都转成带稳定错误码的 {@link NodeFailedException}。</p>
 */
@Component
public class AiAnalyzeNodeExecutor implements NodeExecutor {

    static final int MIN_INPUT_CHARS = 1000;
    static final int MAX_INPUT_CHARS = 32000;
    static final int DEFAULT_INPUT_CHARS = 8000;

    /** 单次 AI 调用的硬上限：模型 HTTP 读超时 180s，节点等待不能更久 */
    static final int MAX_WAIT_SECONDS = 180;

    private static final String MODEL_LABEL = "default";

    /** AI input 的纯路径形态（与 DagValidator.PURE_PATH 保持一致） */
    private static final java.util.regex.Pattern PURE_PATH = java.util.regex.Pattern.compile(
            "^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$");

    private static final String SYSTEM_PROMPT = """
            你是 EasyShell 的运维分析助手。你只分析给定的运维输出并给出结论文本。
            硬性要求：
            1. 不得输出任何可执行命令、脚本片段、shell/SQL/PowerShell 语句，也不得给出可直接复制执行的命令行。
            2. 需要操作建议时，只能用自然语言描述目标与风险，并提示必须走人工审批流程。
            3. 不猜测未提供的数据；信息不足时明确说明。
            4. 输出简洁的中文分析结论与风险等级（低/中/高）。
            """;

    private final ChatModelFactory chatModelFactory;
    private final SensitiveDataFilter sensitiveDataFilter;
    private final AiQuotaService aiQuotaService;
    private final ExpressionEvaluator expressionEvaluator;
    private final ExecutorService aiPool;
    private final WorkflowProperties properties;

    public AiAnalyzeNodeExecutor(ChatModelFactory chatModelFactory,
                                 SensitiveDataFilter sensitiveDataFilter,
                                 AiQuotaService aiQuotaService,
                                 ExpressionEvaluator expressionEvaluator,
                                 @Qualifier("workflowAiPool") ExecutorService aiPool,
                                 WorkflowProperties properties) {
        this.chatModelFactory = chatModelFactory;
        this.sensitiveDataFilter = sensitiveDataFilter;
        this.aiQuotaService = aiQuotaService;
        this.expressionEvaluator = expressionEvaluator;
        this.aiPool = aiPool;
        this.properties = properties;
    }

    @Override
    public NodeType supports() {
        return NodeType.AI_ANALYZE;
    }

    @Override
    public void validate(WorkflowNode node, WorkflowDefinition def, ValidationIssue.Sink issues) {
        String instructions = node.configString("instructions");
        if (instructions == null || instructions.isBlank()) {
            issues.error("NODE_CONFIG_INVALID", "ai-analyze 节点缺少 instructions", node.getKey());
        }
        int maxInputChars = node.configInt("maxInputChars", DEFAULT_INPUT_CHARS);
        if (maxInputChars < MIN_INPUT_CHARS || maxInputChars > MAX_INPUT_CHARS) {
            issues.error("NODE_CONFIG_INVALID",
                    "ai-analyze 节点 maxInputChars 必须在 " + MIN_INPUT_CHARS + ".."
                            + MAX_INPUT_CHARS + " 之间，实际 " + maxInputChars, node.getKey());
        }
    }

    @Override
    public NodeOutcome execute(NodeContext ctx) {
        WorkflowNode node = ctx.node();
        String instructions = node.configString("instructions");
        if (instructions == null || instructions.isBlank()) {
            throw new NodeFailedException("NODE_CONFIG_INVALID", "ai-analyze 节点缺少 instructions");
        }
        int maxInputChars = node.configInt("maxInputChars", DEFAULT_INPUT_CHARS);
        if (maxInputChars < MIN_INPUT_CHARS || maxInputChars > MAX_INPUT_CHARS) {
            throw new NodeFailedException("NODE_CONFIG_INVALID",
                    "ai-analyze 节点 maxInputChars 必须在 " + MIN_INPUT_CHARS + ".."
                            + MAX_INPUT_CHARS + " 之间，实际 " + maxInputChars);
        }
        if (ctx.isCancelled()) {
            throw new NodeCancelledException("运行已取消，停止 AI 分析");
        }

        String inputTemplate = node.configString("input", "");
        String resolvedInput;
        try {
            resolvedInput = resolveInput(inputTemplate, ctx);
        } catch (ExpressionException e) {
            throw new NodeFailedException(e.getCode(), e.getMessage(), e);
        }
        String filteredInput = sensitiveDataFilter.filter(resolvedInput == null ? "" : resolvedInput);
        boolean truncated = filteredInput.length() > maxInputChars;
        if (truncated) {
            filteredInput = filteredInput.substring(0, maxInputChars);
        }

        // 先解析模型：模型不可用/未配置时不能先扣配额
        ChatModel chatModel;
        try {
            chatModel = chatModelFactory.getChatModel(null);
        } catch (RuntimeException e) {
            throw new NodeFailedException("AI_UNAVAILABLE",
                    "AI 模型不可用: " + e.getClass().getSimpleName(), e);
        }
        if (chatModel == null) {
            throw new NodeFailedException("AI_UNAVAILABLE", "AI 模型不可用：未配置默认 Provider");
        }

        String userText = buildUserMessage(instructions, filteredInput);
        int waitSec = Math.max(1, Math.min(
                node.timeoutSecOrDefault(properties.getEngine().getDefaultNodeTimeoutSec()),
                MAX_WAIT_SECONDS));

        // 配额在模型解析之后、池准入之前；池拒绝时退回，绝不为"没跑起来的调用"计费
        try {
            aiQuotaService.checkAndIncrement(ctx.run().getStartedBy(), "workflow-ai-analyze");
        } catch (BusinessException e) {
            throw new NodeFailedException("AI_QUOTA_EXCEEDED",
                    "AI 分析配额检查失败: " + e.getMessage(), e);
        }

        Future<String> future;
        try {
            future = aiPool.submit(() -> callModel(chatModel, userText));
        } catch (RejectedExecutionException e) {
            aiQuotaService.refund(ctx.run().getStartedBy());
            throw new NodeFailedException("AI_UNAVAILABLE", "AI 分析线程池繁忙，请稍后重试");
        }

        String text;
        try {
            text = future.get(waitSec, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new NodeFailedException("AI_TIMEOUT", "AI 分析超过 " + waitSec + " 秒");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new NodeCancelledException("AI 分析等待被中断");
        } catch (CancellationException e) {
            throw new NodeCancelledException("AI 分析已被取消");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof NodeFailedException failed) {
                throw failed;
            }
            if (cause instanceof CancellationException) {
                throw new NodeCancelledException("AI 分析已被取消");
            }
            throw new NodeFailedException("AI_UNAVAILABLE",
                    "AI 分析调用失败: " + cause.getClass().getSimpleName(), cause);
        }
        if (ctx.isCancelled()) {
            throw new NodeCancelledException("运行已取消，丢弃 AI 分析结果");
        }

        String filteredText = sensitiveDataFilter.filter(text == null ? "" : text);
        if (filteredText.isBlank()) {
            throw new NodeFailedException("AI_EMPTY_RESPONSE", "AI 分析返回空结果");
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("text", filteredText);
        output.put("model", MODEL_LABEL);
        output.put("truncated", truncated);
        ctx.variables().putNodeOutput(node.getKey(), node.getOutputVar(), output);
        return NodeOutcome.SUCCEEDED;
    }

    /**
     * AI input 解析：纯路径（如 {@code nodes.check.output}）按路径取值并字符串化，
     * 其余按 {@code ${path}} 模板插值——与 DagValidator 的静态引用识别保持一致。
     */
    private String resolveInput(String inputTemplate, NodeContext ctx) {
        String template = inputTemplate == null ? "" : inputTemplate;
        String trimmed = template.trim();
        if (!template.contains("${") && PURE_PATH.matcher(trimmed).matches()) {
            String path = ExpressionEvaluator.normalizePath(trimmed);
            Object value = ctx.resolver().resolve(path)
                    .orElseThrow(() -> new ExpressionException(ExpressionException.REFERENCE_NOT_FOUND,
                            "引用的变量不存在: " + path));
            java.util.List<String> warnings = new java.util.ArrayList<>();
            String rendered = ValueType.renderToString(value, warnings);
            warnings.forEach(ctx.variables()::addWarning);
            return rendered;
        }
        return expressionEvaluator.renderTemplate(template, Map.of(),
                ctx.resolver(), ctx.variables()::addWarning);
    }

    private String callModel(ChatModel chatModel, String userText) {
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(SYSTEM_PROMPT),
                new UserMessage(userText)));
        ChatResponse response = chatModel.call(prompt);
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null) {
            throw new NodeFailedException("AI_EMPTY_RESPONSE", "AI 分析返回空响应");
        }
        return response.getResult().getOutput().getText();
    }

    private static String buildUserMessage(String instructions, String input) {
        StringBuilder sb = new StringBuilder();
        sb.append("分析任务：\n").append(instructions).append("\n\n");
        if (input == null || input.isBlank()) {
            sb.append("待分析的运维输出：（空）");
        } else {
            sb.append("待分析的运维输出：\n").append(input);
        }
        return sb.toString();
    }
}
