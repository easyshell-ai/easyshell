package com.easyshell.server.workflow.node;

import com.easyshell.server.ai.security.AiQuotaService;
import com.easyshell.server.ai.security.SensitiveDataFilter;
import com.easyshell.server.ai.service.ChatModelFactory;
import com.easyshell.server.workflow.engine.NodeCancelledException;
import com.easyshell.server.workflow.engine.NodeContext;
import com.easyshell.server.workflow.engine.NodeExecutor;
import com.easyshell.server.workflow.engine.NodeFailedException;
import com.easyshell.server.workflow.engine.NodeOutcome;
import com.easyshell.server.workflow.expression.ExpressionEvaluator;
import com.easyshell.server.workflow.expression.ExpressionException;
import com.easyshell.server.workflow.graph.ValidationIssue;
import com.easyshell.server.workflow.model.NodeType;
import com.easyshell.server.workflow.model.WorkflowDefinition;
import com.easyshell.server.workflow.model.WorkflowNode;
import com.easyshell.server.workflow.properties.WorkflowProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * llm 节点执行器（工作流 2.1 §2.1）。
 *
 * <p>通用 LLM 节点：模板提示词 → 模型文本 / 结构化 JSON。复用
 * {@link ChatModelFactory}/{@link AiQuotaService}/{@code workflowAiPool}，输入输出都过
 * {@link SensitiveDataFilter}，不注入任务派发或执行授权依赖，不产生执行能力。</p>
 *
 * <p>json 模式：提示模型只输出 JSON，去 Markdown 围栏后用 Jackson 解析；失败允许
 * <b>一次</b> repair 重试，仍失败则 {@code AI_OUTPUT_INVALID_JSON}。jsonSchema 只作为
 * 提示词约束，不在服务端做 schema 强校验（发布期只对非法 schema 给 WARNING）。</p>
 */
@Component
public class LlmNodeExecutor implements NodeExecutor {

    static final int MIN_INPUT_CHARS = 1000;
    static final int MAX_INPUT_CHARS = 32000;
    static final int DEFAULT_INPUT_CHARS = 8000;
    static final double MIN_TEMPERATURE = 0.0;
    static final double MAX_TEMPERATURE = 2.0;

    private static final String DEFAULT_SYSTEM_PROMPT = """
            你是 EasyShell 的通用 AI 节点。根据用户提示词处理给定的运维数据。
            不输出可执行命令，不猜测未提供的数据；信息不足时明确说明。
            """;

    private final WorkflowAiCaller aiCaller;
    private final SensitiveDataFilter sensitiveDataFilter;
    private final ExpressionEvaluator expressionEvaluator;
    private final WorkflowProperties properties;
    private final ObjectMapper objectMapper;

    public LlmNodeExecutor(ChatModelFactory chatModelFactory,
                           SensitiveDataFilter sensitiveDataFilter,
                           AiQuotaService aiQuotaService,
                           ExpressionEvaluator expressionEvaluator,
                           @Qualifier("workflowAiPool") ExecutorService aiPool,
                           WorkflowProperties properties,
                           ObjectMapper objectMapper) {
        this.aiCaller = new WorkflowAiCaller(chatModelFactory, aiQuotaService, aiPool);
        this.sensitiveDataFilter = sensitiveDataFilter;
        this.expressionEvaluator = expressionEvaluator;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public NodeType supports() {
        return NodeType.LLM;
    }

    @Override
    public void validate(WorkflowNode node, WorkflowDefinition def, ValidationIssue.Sink issues) {
        String userPrompt = node.configString("userPrompt");
        if (userPrompt == null || userPrompt.isBlank()) {
            issues.error("NODE_CONFIG_INVALID", "llm 节点缺少 userPrompt", node.getKey());
        }
        String outputMode = node.configString("outputMode", "text");
        if (!"text".equals(outputMode) && !"json".equals(outputMode)) {
            issues.error("NODE_CONFIG_INVALID",
                    "llm 节点 outputMode 只能是 text / json，实际 '" + outputMode + "'", node.getKey());
        }
        Double temperature = node.configDouble("temperature");
        if (node.getConfig() != null && node.getConfig().containsKey("temperature")) {
            if (temperature == null || !Double.isFinite(temperature)
                    || temperature < MIN_TEMPERATURE || temperature > MAX_TEMPERATURE) {
                issues.error("NODE_CONFIG_INVALID",
                        "llm 节点 temperature 必须在 0..2 之间", node.getKey());
            }
        }
        Object rawMax = node.getConfig() == null ? null : node.getConfig().get("maxInputChars");
        if (rawMax != null) {
            int maxInputChars = node.configInt("maxInputChars", -1);
            if (maxInputChars < MIN_INPUT_CHARS || maxInputChars > MAX_INPUT_CHARS) {
                issues.error("NODE_CONFIG_INVALID",
                        "llm 节点 maxInputChars 必须在 " + MIN_INPUT_CHARS + ".."
                                + MAX_INPUT_CHARS + " 之间，实际 " + maxInputChars, node.getKey());
            }
        }
        validateJsonSchemaHint(node, issues);
    }

    private void validateJsonSchemaHint(WorkflowNode node, ValidationIssue.Sink issues) {
        Object rawSchema = node.getConfig() == null ? null : node.getConfig().get("jsonSchema");
        if (rawSchema == null) {
            return;
        }
        if (!(rawSchema instanceof String schema)) {
            issues.warning("NODE_CONFIG_INVALID",
                    "llm 节点 jsonSchema 不是字符串，已忽略（jsonSchema 仅作为提示词约束）", node.getKey());
            return;
        }
        if (schema.isBlank()) {
            return;
        }
        try {
            objectMapper.readTree(schema);
        } catch (Exception e) {
            issues.warning("NODE_CONFIG_INVALID",
                    "llm 节点 jsonSchema 不是合法 JSON，仅作为文本提示，不会在服务端校验", node.getKey());
        }
    }

    @Override
    public NodeOutcome execute(NodeContext ctx) {
        WorkflowNode node = ctx.node();
        String userPrompt = node.configString("userPrompt");
        if (userPrompt == null || userPrompt.isBlank()) {
            throw new NodeFailedException("NODE_CONFIG_INVALID", "llm 节点缺少 userPrompt");
        }
        String outputMode = node.configString("outputMode", "text");
        if (!"text".equals(outputMode) && !"json".equals(outputMode)) {
            throw new NodeFailedException("NODE_CONFIG_INVALID",
                    "llm 节点 outputMode 只能是 text / json，实际 '" + outputMode + "'");
        }
        Double temperature = node.configDouble("temperature");
        if (temperature != null && (!Double.isFinite(temperature)
                || temperature < MIN_TEMPERATURE || temperature > MAX_TEMPERATURE)) {
            throw new NodeFailedException("NODE_CONFIG_INVALID",
                    "llm 节点 temperature 必须在 0..2 之间，实际 " + temperature);
        }
        int maxInputChars = node.configInt("maxInputChars", DEFAULT_INPUT_CHARS);
        if (maxInputChars < MIN_INPUT_CHARS || maxInputChars > MAX_INPUT_CHARS) {
            throw new NodeFailedException("NODE_CONFIG_INVALID",
                    "llm 节点 maxInputChars 必须在 " + MIN_INPUT_CHARS + ".."
                            + MAX_INPUT_CHARS + " 之间，实际 " + maxInputChars);
        }
        if (ctx.isCancelled()) {
            throw new NodeCancelledException("运行已取消，停止 LLM 调用");
        }

        String provider = node.configString("provider");
        String model = node.configString("model");
        String resolvedPrompt;
        try {
            resolvedPrompt = NodeTemplateResolver.resolveText(userPrompt, ctx, expressionEvaluator);
        } catch (ExpressionException e) {
            throw new NodeFailedException(e.getCode(), e.getMessage(), e);
        }
        String filteredInput = sensitiveDataFilter.filter(resolvedPrompt == null ? "" : resolvedPrompt);
        if (filteredInput.length() > maxInputChars) {
            filteredInput = filteredInput.substring(0, maxInputChars);
        }

        String systemPrompt = node.configString("systemPrompt");
        if (systemPrompt == null || systemPrompt.isBlank()) {
            systemPrompt = DEFAULT_SYSTEM_PROMPT;
        }
        if ("json".equals(outputMode)) {
            Object rawSchema = node.getConfig() == null ? null : node.getConfig().get("jsonSchema");
            String schema = rawSchema instanceof String value ? value : null;
            systemPrompt = systemPrompt + "\n\n" + AiStructuredOutput.jsonInstruction(schema);
        }

        ChatModel chatModel = aiCaller.resolveModel(provider, model, temperature);
        int waitSec = Math.max(1, Math.min(
                node.timeoutSecOrDefault(properties.getEngine().getDefaultNodeTimeoutSec()),
                WorkflowAiCaller.MAX_WAIT_SECONDS));

        String rawText = aiCaller.call(ctx.run().getStartedBy(), "workflow-llm", chatModel,
                systemPrompt, filteredInput, waitSec, WorkflowAiCaller.MAX_WAIT_SECONDS);
        if (ctx.isCancelled()) {
            throw new NodeCancelledException("运行已取消，丢弃 LLM 结果");
        }
        String raw = rawText == null ? "" : rawText;
        String modelLabel = AiStructuredOutput.modelLabel(provider, model);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("model", modelLabel);

        if ("text".equals(outputMode)) {
            output.put("text", sensitiveDataFilter.filter(raw));
            output.put("json", null);
            output.put("structured", false);
        } else {
            Object parsed;
            try {
                parsed = AiStructuredOutput.parseJson(raw, objectMapper);
            } catch (Exception firstError) {
                if (ctx.isCancelled()) {
                    throw new NodeCancelledException("运行已取消，停止 JSON 修复");
                }
                String repaired = repairJson(ctx, chatModel, systemPrompt, filteredInput,
                        sensitiveDataFilter.filter(raw), firstError, waitSec);
                try {
                    parsed = AiStructuredOutput.parseJson(repaired, objectMapper);
                } catch (Exception secondError) {
                    throw new NodeFailedException("AI_OUTPUT_INVALID_JSON",
                            "模型输出无法解析为 JSON（已尝试一次修复）");
                }
                raw = repaired;
            }
            if (parsed == null) {
                throw new NodeFailedException("AI_OUTPUT_INVALID_JSON", "模型输出的 JSON 为 null");
            }
            output.put("text", sensitiveDataFilter.filter(raw));
            output.put("json", AiStructuredOutput.maskJson(parsed, sensitiveDataFilter));
            output.put("structured", true);
        }

        ctx.variables().putNodeOutput(node.getKey(), node.getOutputVar(), output);
        return NodeOutcome.SUCCEEDED;
    }

    private String repairJson(NodeContext ctx, ChatModel chatModel, String systemPrompt,
                              String originalPrompt, String previousOutput, Exception parseError,
                              int waitSec) {
        String error = parseError == null ? "未知解析错误" : parseError.getClass().getSimpleName();
        String repairPrompt = originalPrompt
                + "\n\n你上一次的输出无法解析为 JSON（错误：" + error + "）。"
                + "\n上一次输出：\n" + previousOutput
                + "\n\n请只输出修正后的合法 JSON，不要输出 Markdown 代码块或解释文字。";
        String repaired = aiCaller.call(ctx.run().getStartedBy(), "workflow-llm-repair", chatModel,
                systemPrompt, repairPrompt, waitSec, WorkflowAiCaller.MAX_WAIT_SECONDS);
        return repaired == null ? "" : repaired;
    }
}
