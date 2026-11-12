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
import com.fasterxml.jackson.databind.ObjectMapper;

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
    private final ObjectMapper objectMapper;

    public AiAnalyzeNodeExecutor(ChatModelFactory chatModelFactory,
                                 SensitiveDataFilter sensitiveDataFilter,
                                 AiQuotaService aiQuotaService,
                                 ExpressionEvaluator expressionEvaluator,
                                 @Qualifier("workflowAiPool") ExecutorService aiPool,
                                 WorkflowProperties properties,
                                 ObjectMapper objectMapper) {
        this.chatModelFactory = chatModelFactory;
        this.sensitiveDataFilter = sensitiveDataFilter;
        this.aiQuotaService = aiQuotaService;
        this.expressionEvaluator = expressionEvaluator;
        this.aiPool = aiPool;
        this.properties = properties;
        this.objectMapper = objectMapper;
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
        String outputMode = node.configString("outputMode", "text");
        if (!"text".equals(outputMode) && !"json".equals(outputMode) && !AiJsonSchemaV1.MODE.equals(outputMode)) {
            issues.error("NODE_CONFIG_INVALID",
                    "ai-analyze 节点 outputMode 只能是 text / json / json_schema_v1，实际 '" + outputMode + "'", node.getKey());
        }
        Object rawSchema = node.getConfig() == null ? null : node.getConfig().get("jsonSchema");
        if (AiJsonSchemaV1.MODE.equals(outputMode)) {
            try { new AiJsonSchemaV1(rawSchema, objectMapper); }
            catch (IllegalArgumentException e) { issues.error("NODE_CONFIG_INVALID", "无效的严格 JSON Schema", node.getKey()); }
        } else if (rawSchema instanceof String schema && !schema.isBlank()) {
            try {
                objectMapper.readTree(schema);
            } catch (Exception e) {
                issues.warning("NODE_CONFIG_INVALID",
                        "ai-analyze 节点 jsonSchema 不是合法 JSON，仅作为文本提示，不会在服务端校验",
                        node.getKey());
            }
        } else if (rawSchema != null && !(rawSchema instanceof String)) {
            issues.warning("NODE_CONFIG_INVALID",
                    "ai-analyze 节点 jsonSchema 不是字符串，已忽略（仅作为提示词约束）", node.getKey());
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

        String outputMode = node.configString("outputMode", "text");
        if (!"text".equals(outputMode) && !"json".equals(outputMode) && !AiJsonSchemaV1.MODE.equals(outputMode)) {
            throw new NodeFailedException("NODE_CONFIG_INVALID",
                    "ai-analyze 节点 outputMode 只能是 text / json / json_schema_v1，实际 '" + outputMode + "'");
        }
        AiJsonSchemaV1 strict = null;
        if (AiJsonSchemaV1.MODE.equals(outputMode)) {
            try { strict = new AiJsonSchemaV1(node.getConfig().get("jsonSchema"), objectMapper); }
            catch (IllegalArgumentException e) { throw new NodeFailedException("NODE_CONFIG_INVALID", "无效的严格 JSON Schema"); }
        }
        String systemPrompt = SYSTEM_PROMPT;
        if ("json".equals(outputMode)) {
            Object rawSchema = node.getConfig() == null ? null : node.getConfig().get("jsonSchema");
            String schema = rawSchema instanceof String value ? value : null;
            systemPrompt = systemPrompt + "\n\n" + AiStructuredOutput.jsonInstruction(schema);
        }

        if (strict != null) {
            systemPrompt += "\n\n只输出符合以下 schema 的 JSON 对象：\n"
                    + sensitiveDataFilter.filter(node.configString("jsonSchema"));
        }
        systemPrompt = sensitiveDataFilter.filter(systemPrompt);
        String userText = buildUserMessage(sensitiveDataFilter.filter(instructions), filteredInput);
        int waitSec = Math.max(1, Math.min(
                node.timeoutSecOrDefault(properties.getEngine().getDefaultNodeTimeoutSec()),
                MAX_WAIT_SECONDS));

        String text = callWithBudget(chatModel, systemPrompt, userText, ctx.run().getStartedBy(),
                waitSec, "workflow-ai-analyze", "AI 分析");
        if (ctx.isCancelled()) {
            throw new NodeCancelledException("运行已取消，丢弃 AI 分析结果");
        }

        String raw = text == null ? "" : text;
        String filteredText = sensitiveDataFilter.filter(raw);
        if (filteredText.isBlank()) {
            throw new NodeFailedException("AI_EMPTY_RESPONSE", "AI 分析返回空结果");
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("text", filteredText);
        output.put("model", MODEL_LABEL);
        output.put("truncated", truncated);
        if (strict != null) {
            try {
                output.put("model", sensitiveDataFilter.filter((String) output.get("model")));
                Object parsed = strict.parseAndMask(raw, sensitiveDataFilter);
                output.put("text", objectMapper.writeValueAsString(parsed));
                output.put("json", parsed);
                output.put("structured", true);
                strict.checkEnvelope(output);
            } catch (Exception e) {
                throw new NodeFailedException("AI_OUTPUT_INVALID_JSON", "严格 JSON 输出无效或超限");
            }
        } else if ("json".equals(outputMode)) {
            Object parsed;
            try {
                parsed = AiStructuredOutput.parseJson(raw, objectMapper);
            } catch (Exception firstError) {
                if (ctx.isCancelled()) {
                    throw new NodeCancelledException("运行已取消，停止 JSON 修复");
                }
                String repairPrompt = userText
                        + "\n\n你上一次的输出无法解析为 JSON（错误："
                        + firstError.getClass().getSimpleName() + "）。\n上一次输出：\n[无法安全展示无效 JSON]"
                        + "\n\n请只输出修正后的合法 JSON，不要输出 Markdown 代码块或解释文字。";
                String repaired = callWithBudget(chatModel, systemPrompt, sensitiveDataFilter.filter(repairPrompt),
                        ctx.run().getStartedBy(), waitSec, "workflow-ai-analyze-repair", "AI 修复");
                raw = repaired == null ? "" : repaired;
                try {
                    parsed = AiStructuredOutput.parseJson(raw, objectMapper);
                } catch (Exception secondError) {
                    throw new NodeFailedException("AI_OUTPUT_INVALID_JSON",
                            "模型输出无法解析为 JSON（已尝试一次修复）");
                }
                filteredText = sensitiveDataFilter.filter(raw);
            }
            if (parsed == null) {
                throw new NodeFailedException("AI_OUTPUT_INVALID_JSON", "模型输出的 JSON 为 null");
            }
            Object masked = AiStructuredOutput.maskJson(parsed, sensitiveDataFilter);
            try { output.put("text", objectMapper.writeValueAsString(masked)); }
            catch (Exception e) { throw new NodeFailedException("AI_OUTPUT_INVALID_JSON", "模型输出无法安全序列化"); }
            output.put("json", masked);
            output.put("structured", true);
        } else {
            output.put("json", null);
            output.put("structured", false);
        }
        if (ctx.isCancelled()) throw new NodeCancelledException("运行已取消，丢弃 AI 分析结果");
        ctx.variables().putNodeOutput(node.getKey(), node.getOutputVar(), output);
        return NodeOutcome.SUCCEEDED;
    }

    /** 模型调用边界：配额 → workflowAiPool → 超时/取消/错误码映射（repair 复用同一口径）。 */
    private String callWithBudget(ChatModel chatModel, String systemPrompt, String userText,
                                  Long userId, int waitSec, String action, String label) {
        // 配额在模型解析之后、池准入之前；池拒绝时退回，绝不为"没跑起来的调用"计费
        try {
            aiQuotaService.checkAndIncrement(userId, action);
        } catch (BusinessException e) {
            throw new NodeFailedException("AI_QUOTA_EXCEEDED",
                    label + "配额检查失败: " + e.getMessage(), e);
        }

        Future<String> future;
        try {
            future = aiPool.submit(() -> callModel(chatModel, systemPrompt, userText));
        } catch (RejectedExecutionException e) {
            aiQuotaService.refund(userId);
            throw new NodeFailedException("AI_UNAVAILABLE", label + "线程池繁忙，请稍后重试");
        }

        try {
            return future.get(waitSec, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new NodeFailedException("AI_TIMEOUT", label + "超过 " + waitSec + " 秒");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new NodeCancelledException(label + "等待被中断");
        } catch (CancellationException e) {
            throw new NodeCancelledException(label + "已被取消");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof NodeFailedException failed) {
                throw failed;
            }
            if (cause instanceof CancellationException) {
                throw new NodeCancelledException(label + "已被取消");
            }
            throw new NodeFailedException("AI_UNAVAILABLE",
                    label + "调用失败: " + cause.getClass().getSimpleName(), cause);
        }
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

    private String callModel(ChatModel chatModel, String systemPrompt, String userText) {
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(systemPrompt),
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
