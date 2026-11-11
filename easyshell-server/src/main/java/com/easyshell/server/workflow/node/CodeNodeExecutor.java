package com.easyshell.server.workflow.node;

import com.easyshell.server.ai.security.SensitiveDataFilter;
import com.easyshell.server.workflow.engine.NodeContext;
import com.easyshell.server.workflow.engine.NodeCancelledException;
import com.easyshell.server.workflow.engine.NodeExecutor;
import com.easyshell.server.workflow.engine.NodeFailedException;
import com.easyshell.server.workflow.engine.NodeOutcome;
import com.easyshell.server.workflow.expression.ExpressionEvaluator;
import com.easyshell.server.workflow.expression.ExpressionException;
import com.easyshell.server.workflow.graph.ValidationIssue;
import com.easyshell.server.workflow.model.NodeType;
import com.easyshell.server.workflow.model.WorkflowDefinition;
import com.easyshell.server.workflow.model.WorkflowNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Code nodes are disabled until execution has a hard memory isolation boundary. */
@Component
public class CodeNodeExecutor implements NodeExecutor, AutoCloseable {

    private static final String RUNTIME_UNAVAILABLE = "code 节点运行时不可用：缺少硬内存隔离，代码未执行";

    static final int MAX_CODE_CHARS = 20_000;
    static final int MIN_TIMEOUT_SEC = 1;
    static final int MAX_TIMEOUT_SEC = 5;
    static final int DEFAULT_TIMEOUT_SEC = 5;
    static final int MAX_INPUT_JSON_CHARS = 1_000_000;
    static final int MAX_JSON_DEPTH = 64;
    static final int MAX_JSON_ELEMENTS = 10_000;
    static final Pattern INPUT_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,63}$");

    private final ExpressionEvaluator expressionEvaluator;
    private final ObjectMapper objectMapper;
    private final SensitiveDataFilter sensitiveDataFilter;
    private com.easyshell.server.workflow.code.WorkflowCodeWorkerClient workerClient;

    @org.springframework.beans.factory.annotation.Autowired
    void setWorkerClient(com.easyshell.server.workflow.code.WorkflowCodeWorkerClient workerClient) {
        this.workerClient = workerClient;
    }

    public CodeNodeExecutor(ExpressionEvaluator expressionEvaluator,
                            ObjectMapper objectMapper,
                            SensitiveDataFilter sensitiveDataFilter) {
        this.expressionEvaluator = expressionEvaluator;
        this.objectMapper = objectMapper;
        this.sensitiveDataFilter = sensitiveDataFilter;
    }

    @Override
    public NodeType supports() {
        return NodeType.CODE;
    }

    // ==================================================================
    // 发布期校验
    // ==================================================================

    @Override
    public void validate(WorkflowNode node, WorkflowDefinition def, ValidationIssue.Sink issues) {
        if (workerClient == null || !workerClient.isAvailable()) {
            issues.error("CODE_RUNTIME_UNAVAILABLE", RUNTIME_UNAVAILABLE, node.getKey());
        }
        String language = node.configString("language");
        if (!isSupportedLanguage(language)) {
            issues.error("NODE_CONFIG_INVALID",
                    "code 节点 language 只能是 javascript / python，实际 '" + language + "'", node.getKey());
        }
        String code = node.configString("code");
        if (code == null || code.isBlank()) {
            issues.error("NODE_CONFIG_INVALID", "code 节点缺少 code", node.getKey());
        } else if (code.length() > MAX_CODE_CHARS) {
            issues.error("NODE_CONFIG_INVALID",
                    "code 节点代码长度必须 <= " + MAX_CODE_CHARS + " 字符，实际 " + code.length(),
                    node.getKey());
        }
        validateInputs(node, issues);
        int timeout;
        try { timeout = guestTimeout(node); }
        catch (NodeFailedException invalid) {
            issues.error(invalid.getCode(), invalid.getMessage(), node.getKey());
            return;
        }
        if (timeout < MIN_TIMEOUT_SEC || timeout > MAX_TIMEOUT_SEC) {
            issues.error("NODE_CONFIG_INVALID",
                    "code 节点 timeoutSec 必须在 " + MIN_TIMEOUT_SEC + ".." + MAX_TIMEOUT_SEC
                            + " 之间，实际 " + timeout, node.getKey());
        }
    }

    private void validateInputs(WorkflowNode node, ValidationIssue.Sink issues) {
        Object raw = node.getConfig() == null ? null : node.getConfig().get("inputs");
        if (raw == null) {
            return;   // inputs 可选，默认空对象
        }
        if (!(raw instanceof Map<?, ?> map)) {
            issues.error("NODE_CONFIG_INVALID", "code 节点 inputs 必须是对象", node.getKey());
            return;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String name = String.valueOf(entry.getKey());
            if (!INPUT_NAME.matcher(name).matches()) {
                issues.error("NODE_CONFIG_INVALID",
                        "code 节点输入名 '" + name + "' 不合规：必须匹配 ^[A-Za-z_][A-Za-z0-9_]{0,63}$",
                        node.getKey());
            }
            if (!(entry.getValue() instanceof String)) {
                issues.error("NODE_CONFIG_INVALID",
                        "code 节点输入 '" + name + "' 的值必须是模板字符串", node.getKey());
            }
        }
    }

    // ==================================================================
    // 执行
    // ==================================================================

    @Override
    public NodeOutcome execute(NodeContext ctx) {
        if (ctx.isCancelled()) throw new NodeCancelledException("运行已取消，停止代码执行");
        Object result = executeRemote(ctx.node(), resolveInputs(ctx.node(), ctx), ctx::isCancelled);
        if (ctx.isCancelled()) throw new NodeCancelledException("运行已取消，丢弃代码结果");
        ctx.variables().putNodeOutput(ctx.node().getKey(), ctx.node().getOutputVar(), result);
        return NodeOutcome.SUCCEEDED;
    }

    /** Sample execution is disabled at the entry point, independently of request validation. */
    public Object executeSample(WorkflowNode node, Map<String, Object> input) {
        return executeRemote(node, input);
    }

    private Object executeRemote(WorkflowNode node, Map<String, Object> input) {
        return executeRemote(node, input, () -> false);
    }

    private Object executeRemote(WorkflowNode node, Map<String, Object> input, java.util.function.BooleanSupplier cancelled) {
        if (workerClient == null || !workerClient.isAvailable()) {
            throw new NodeFailedException("CODE_RUNTIME_UNAVAILABLE", RUNTIME_UNAVAILABLE);
        }
        String language = node.configString("language");
        String source = node.configString("code");
        int timeout = guestTimeout(node);
        if (!isSupportedLanguage(language) || source == null || source.isBlank()
                || source.length() > MAX_CODE_CHARS || timeout < 1 || timeout > 5 || input == null) {
            throw new NodeFailedException("NODE_CONFIG_INVALID", "代码配置无效或超过隔离执行限制");
        }
        var active = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Object, Boolean>());
        checkInput(input, 0, new Budget("CODE_INPUT_INVALID"), active);
        Object masked = sensitiveDataFilter.filterJson(input);
        @SuppressWarnings("unchecked")
        Map<String, Object> safeInput = (Map<String, Object>) masked;
        var result = workerClient.execute(language, source, safeInput, timeout * 1000, cancelled);
        if (!result.succeeded()) {
            throw new NodeFailedException(result.errorCode() == null ? "CODE_EXECUTION_FAILED" : result.errorCode(),
                    "隔离代码执行失败");
        }
        Object output = sensitiveDataFilter.filterJson(result.output());
        if (!(output instanceof Map<?, ?>)) {
            Map<String, Object> wrapped = new LinkedHashMap<>();
            wrapped.put("result", output);
            output = wrapped;
        }
        try {
            if (objectMapper.writeValueAsBytes(output).length > 8192) {
                throw new NodeFailedException("CODE_OUTPUT_TOO_LARGE", "代码结果超过完整变量存储上限 8192 字节");
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new NodeFailedException("CODE_OUTPUT_INVALID", "代码结果无法序列化");
        }
        return output;
    }

    private int guestTimeout(WorkflowNode node) {
        Object raw = node.getConfig() == null ? null : node.getConfig().get("timeoutSec");
        if (raw == null) return DEFAULT_TIMEOUT_SEC;
        if (!(raw instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() != number.intValue() || number.intValue() < 1 || number.intValue() > 5) {
            throw new NodeFailedException("CODE_TIMEOUT_UNSUPPORTED", "隔离代码执行上限为 5 秒，请更新超时配置");
        }
        return number.intValue();
    }

    private Map<String, Object> resolveInputs(WorkflowNode node, NodeContext ctx) {
        Map<String, Object> input = new LinkedHashMap<>();
        Object rawValue = node.getConfig() == null ? null : node.getConfig().get("inputs");
        if (rawValue != null && !(rawValue instanceof Map<?, ?>)) {
            throw new NodeFailedException("NODE_CONFIG_INVALID", "code 节点 inputs 必须是对象");
        }
        Map<String, Object> rawInputs = node.configMap("inputs");
        if (rawInputs == null) {
            return input;
        }
        for (Map.Entry<String, Object> entry : rawInputs.entrySet()) {
            String name = entry.getKey();
            if (!INPUT_NAME.matcher(name).matches()) {
                throw new NodeFailedException("NODE_CONFIG_INVALID",
                        "code 节点输入名 '" + name + "' 不合规");
            }
            if (!(entry.getValue() instanceof String template)) {
                throw new NodeFailedException("NODE_CONFIG_INVALID",
                        "code 节点输入 '" + name + "' 的值必须是模板字符串");
            }
            try {
                // Preserve sensitive source-field context before aliasing it to an innocuous
                // input name. The sole masking policy remains SensitiveDataFilter.
                var references = java.util.regex.Pattern.compile("\\$\\{([^{}]+)}").matcher(template);
                boolean sensitiveSource = false;
                while (references.find()) {
                    for (String component : references.group(1).trim().split("\\.")) {
                        if (sensitiveDataFilter.isSensitiveField(component)) sensitiveSource = true;
                    }
                }
                if (sensitiveSource) {
                    input.put(name, sensitiveDataFilter.filterField("token", "redacted"));
                    continue;
                }
                input.put(name, NodeTemplateResolver.resolveValue(template, ctx, expressionEvaluator));
            } catch (ExpressionException e) {
                throw new NodeFailedException(e.getCode(), e.getMessage(), e);
            }
        }
        return input;
    }

    private void checkInput(Object value, int depth, Budget budget, Set<Object> active) {
        budget.element(depth);
        if (value == null || value instanceof Boolean) return;
        if (value instanceof String text) {
            budget.text(text);
            return;
        }
        if (value instanceof Number number) {
            if (!Double.isFinite(number.doubleValue())) {
                throw new NodeFailedException("CODE_INPUT_INVALID", "code 节点输入包含非有限数值");
            }
            budget.text(number.toString());
            return;
        }
        if (!(value instanceof Map<?, ?>) && !(value instanceof List<?>)) {
            throw new NodeFailedException("CODE_INPUT_INVALID", "code 节点输入必须是 JSON 类型");
        }
        if (!active.add(value)) {
            throw new NodeFailedException("CODE_INPUT_INVALID", "code 节点输入包含循环引用");
        }
        try {
            if (value instanceof Map<?, ?> map) {
                budget.containerSize(map.size());
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) {
                        throw new NodeFailedException("CODE_INPUT_INVALID", "code 节点输入对象的键必须是字符串");
                    }
                    budget.text(key);
                    checkInput(entry.getValue(), depth + 1, budget, active);
                }
            } else {
                List<?> list = (List<?>) value;
                budget.containerSize(list.size());
                for (Object item : list) checkInput(item, depth + 1, budget, active);
            }
        } finally {
            active.remove(value);
        }
    }

    /** Conservative JSON upper bound, including worst-case string escaping and punctuation. */
    private static final class Budget {
        private final String code;
        private int elements;
        private long chars;
        Budget(String code) { this.code = code; }
        void element(int depth) {
            if (depth > MAX_JSON_DEPTH || ++elements > MAX_JSON_ELEMENTS) fail();
            chars += 32; // JSON punctuation and largest finite double representation
            if (chars > MAX_INPUT_JSON_CHARS) fail();
        }
        void text(String text) {
            chars += 6L * text.length() + 3;
            if (chars > MAX_INPUT_JSON_CHARS) fail();
        }
        void containerSize(long size) {
            if (size > MAX_JSON_ELEMENTS - elements) fail();
        }
        private void fail() {
            throw new NodeFailedException(code, "code 节点 JSON 超过大小、深度或元素数量限制");
        }
    }

    private static boolean isSupportedLanguage(String language) {
        return "javascript".equals(language) || "python".equals(language);
    }

    @Override
    @PreDestroy
    public void close() {
    }
}
