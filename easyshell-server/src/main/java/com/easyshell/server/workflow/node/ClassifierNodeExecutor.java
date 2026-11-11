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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.regex.Pattern;

/**
 * classifier 节点执行器（工作流 2.1 §2.2，Dify 风格问题分类器）。
 *
 * <p>模型必须返回 {@code {"class":"<name>","reason":"..."}}；class 不在配置类别内、
 * 缺失或无法解析时 <b>fail-closed</b>（{@code AI_CLASSIFY_INVALID}），绝不静默路由到
 * 任意分支。输出 {@code {class, reason, raw}}，全部经过 {@link SensitiveDataFilter}。</p>
 */
@Component
public class ClassifierNodeExecutor implements NodeExecutor {

    static final Pattern CLASS_NAME = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");
    static final int MIN_CLASSES = 1;
    static final int MAX_CLASSES = 10;
    static final int DEFAULT_MAX_INPUT_CHARS = 8000;
    static final int MAX_INPUT_CHARS = 32000;

    private static final String DEFAULT_INSTRUCTIONS =
            "Classify the operations input into exactly one class.";

    private final WorkflowAiCaller aiCaller;
    private final SensitiveDataFilter sensitiveDataFilter;
    private final ExpressionEvaluator expressionEvaluator;
    private final WorkflowProperties properties;
    private final ObjectMapper objectMapper;

    public ClassifierNodeExecutor(ChatModelFactory chatModelFactory,
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
        return NodeType.CLASSIFIER;
    }

    @Override
    public void validate(WorkflowNode node, WorkflowDefinition def, ValidationIssue.Sink issues) {
        String input = node.configString("input");
        if (input == null || input.isBlank()) {
            issues.error("NODE_CONFIG_INVALID", "classifier 节点缺少 input", node.getKey());
        }
        List<ClassSpec> classes = parseClasses(node);
        if (classes.size() < MIN_CLASSES || classes.size() > MAX_CLASSES) {
            issues.error("NODE_CONFIG_INVALID",
                    "classifier 节点 classes 数量必须在 " + MIN_CLASSES + ".." + MAX_CLASSES
                            + " 之间，实际 " + classes.size(), node.getKey());
        }
        Set<String> seen = new LinkedHashSet<>();
        for (ClassSpec spec : classes) {
            if (spec.name() == null || !CLASS_NAME.matcher(spec.name()).matches()) {
                issues.error("NODE_CONFIG_INVALID",
                        "classifier 类别名 '" + spec.name() + "' 不合规：必须匹配 ^[a-z][a-z0-9_]{0,63}$",
                        node.getKey());
            } else if ("default".equals(spec.name())) {
                issues.error("NODE_CONFIG_INVALID",
                        "classifier 类别名 default 是保留 handle，请改用其他名称", node.getKey());
            } else if (!seen.add(spec.name())) {
                issues.error("NODE_CONFIG_INVALID", "classifier 类别名重复: " + spec.name(), node.getKey());
            }
        }
    }

    @Override
    public NodeOutcome execute(NodeContext ctx) {
        WorkflowNode node = ctx.node();
        String inputTemplate = node.configString("input");
        if (inputTemplate == null || inputTemplate.isBlank()) {
            throw new NodeFailedException("NODE_CONFIG_INVALID", "classifier 节点缺少 input");
        }
        List<ClassSpec> classes = parseClasses(node);
        if (classes.size() < MIN_CLASSES || classes.size() > MAX_CLASSES) {
            throw new NodeFailedException("NODE_CONFIG_INVALID",
                    "classifier 节点 classes 数量必须在 " + MIN_CLASSES + ".." + MAX_CLASSES
                            + " 之间，实际 " + classes.size());
        }
        Set<String> classNames = new LinkedHashSet<>();
        for (ClassSpec spec : classes) {
            if (spec.name() == null || !CLASS_NAME.matcher(spec.name()).matches()) {
                throw new NodeFailedException("NODE_CONFIG_INVALID",
                        "classifier 类别名不合规: " + spec.name());
            }
            if ("default".equals(spec.name())) {
                throw new NodeFailedException("NODE_CONFIG_INVALID",
                        "classifier 类别名 default 是保留 handle");
            }
            if (!classNames.add(spec.name())) {
                throw new NodeFailedException("NODE_CONFIG_INVALID",
                        "classifier 类别名重复: " + spec.name());
            }
        }
        if (ctx.isCancelled()) {
            throw new NodeCancelledException("运行已取消，停止分类");
        }

        String resolvedInput;
        try {
            resolvedInput = NodeTemplateResolver.resolveText(inputTemplate, ctx, expressionEvaluator);
        } catch (ExpressionException e) {
            throw new NodeFailedException(e.getCode(), e.getMessage(), e);
        }
        String filteredInput = sensitiveDataFilter.filter(resolvedInput == null ? "" : resolvedInput);
        int maxInputChars = Math.max(1000, Math.min(
                node.configInt("maxInputChars", DEFAULT_MAX_INPUT_CHARS), MAX_INPUT_CHARS));
        if (filteredInput.length() > maxInputChars) {
            filteredInput = filteredInput.substring(0, maxInputChars);
        }

        String instructions = node.configString("instructions");
        if (instructions == null || instructions.isBlank()) {
            instructions = DEFAULT_INSTRUCTIONS;
        }
        String systemPrompt = buildSystemPrompt(instructions, classes);
        String provider = node.configString("provider");
        String model = node.configString("model");

        ChatModel chatModel = aiCaller.resolveModel(provider, model, null);
        int waitSec = Math.max(1, Math.min(
                node.timeoutSecOrDefault(properties.getEngine().getDefaultNodeTimeoutSec()),
                WorkflowAiCaller.MAX_WAIT_SECONDS));
        String raw = aiCaller.call(ctx.run().getStartedBy(), "workflow-classifier", chatModel,
                systemPrompt, filteredInput, waitSec, WorkflowAiCaller.MAX_WAIT_SECONDS);
        if (ctx.isCancelled()) {
            throw new NodeCancelledException("运行已取消，丢弃分类结果");
        }

        String rawText = raw == null ? "" : raw;
        Object parsed;
        try {
            // 先解析原文再脱敏：掩码不应改变模型输出的 JSON 结构
            parsed = AiStructuredOutput.parseJson(rawText, objectMapper);
        } catch (Exception e) {
            throw new NodeFailedException("AI_CLASSIFY_INVALID",
                    "分类器输出不是合法 JSON（fail-closed）");
        }
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new NodeFailedException("AI_CLASSIFY_INVALID",
                    "分类器输出不是 JSON 对象（fail-closed）");
        }
        Object rawClass = map.get("class");
        if (!(rawClass instanceof String selected) || !classNames.contains(selected)) {
            throw new NodeFailedException("AI_CLASSIFY_INVALID",
                    "分类器返回的 class 不在配置类别中（fail-closed）");
        }
        Object rawReason = map.get("reason");
        String reason;

        String maskedRaw;
        try {
            maskedRaw = objectMapper.writeValueAsString(AiStructuredOutput.maskJson(parsed, sensitiveDataFilter));
            Object safeReason = AiStructuredOutput.maskJson(rawReason, sensitiveDataFilter);
            reason = safeReason == null ? "" : String.valueOf(safeReason);
        } catch (Exception e) {
            throw new NodeFailedException("AI_CLASSIFY_INVALID", "分类器输出无法安全处理（fail-closed）");
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("class", selected);
        output.put("reason", reason);
        output.put("raw", maskedRaw);
        ctx.variables().putNodeOutput(node.getKey(), node.getOutputVar(), output);
        return NodeOutcome.SUCCEEDED;
    }

    private String buildSystemPrompt(String instructions, List<ClassSpec> classes) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是问题分类器。\n").append(sensitiveDataFilter.filter(instructions)).append("\n\n可选类别：\n");
        for (ClassSpec spec : classes) {
            sb.append("- ").append(sensitiveDataFilter.filter(spec.name()));
            if (spec.description() != null && !spec.description().isBlank()) {
                sb.append(": ").append(sensitiveDataFilter.filter(spec.description()));
            }
            sb.append('\n');
        }
        sb.append("\n只输出一个 JSON 对象，格式：{\"class\":\"<类别名>\",\"reason\":\"<简短理由>\"}。")
                .append("class 必须是上述类别名之一。不要输出 Markdown 代码块或任何解释文字。");
        return sb.toString();
    }

    /** classes: [{name, description}]；非法行保留 name=null 交由 validate/execute 报错。 */
    static List<ClassSpec> parseClasses(WorkflowNode node) {
        List<Object> rows = node.configList("classes");
        List<ClassSpec> result = new ArrayList<>();
        if (rows == null) {
            return result;
        }
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> map)) {
                result.add(new ClassSpec(null, null));
                continue;
            }
            Object name = map.get("name");
            Object description = map.get("description");
            result.add(new ClassSpec(name == null ? null : String.valueOf(name),
                    description == null ? null : String.valueOf(description)));
        }
        return result;
    }

    record ClassSpec(String name, String description) {
    }
}
