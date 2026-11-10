package com.easyshell.server.workflow.node;

import com.easyshell.server.ai.security.SensitiveDataFilter;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * code 节点执行器（工作流 2.1 §2.3）：GraalVM Polyglot 沙箱内的数据变换。
 *
 * <p><b>安全边界</b>：{@code HostAccess.NONE}、{@code allowIO=false}、
 * environment/process/thread 全部关闭；不在宿主线程执行、不落 shell、失败时绝不回退到
 * 命令行。语言运行时不可用 → {@code CODE_RUNTIME_UNAVAILABLE}，不静默伪造结果。</p>
 *
 * <p><b>契约</b>：javascript 代码定义 {@code function main(input)}，python 定义
 * {@code def main(input)}；输入按 code.inputs 模板解析，返回值经 Jackson 序列化/反序列化
 * 后写入节点输出。超时通过 {@code context.close(true)} 强制取消。</p>
 */
@Component
public class CodeNodeExecutor implements NodeExecutor, AutoCloseable {

    static final int MAX_CODE_CHARS = 20_000;
    static final int MIN_TIMEOUT_SEC = 1;
    static final int MAX_TIMEOUT_SEC = 15;
    static final int DEFAULT_TIMEOUT_SEC = 5;
    static final int MAX_INPUT_JSON_CHARS = 1_000_000;
    static final Pattern INPUT_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,63}$");

    private static final String JS = "js";
    private static final String PYTHON = "python";

    private final ExpressionEvaluator expressionEvaluator;
    private final ObjectMapper objectMapper;
    private final SensitiveDataFilter sensitiveDataFilter;
    private final ExecutorService codePool;
    private final AtomicInteger threadCounter = new AtomicInteger(1);

    private volatile Engine engine;

    public CodeNodeExecutor(ExpressionEvaluator expressionEvaluator,
                            ObjectMapper objectMapper,
                            SensitiveDataFilter sensitiveDataFilter) {
        this.expressionEvaluator = expressionEvaluator;
        this.objectMapper = objectMapper;
        this.sensitiveDataFilter = sensitiveDataFilter;
        this.codePool = new ThreadPoolExecutor(
                2, 4, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(16),
                runnable -> {
                    Thread thread = new Thread(runnable,
                            "workflow-code-" + threadCounter.getAndIncrement());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
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
        int timeout = node.configInt("timeoutSec", DEFAULT_TIMEOUT_SEC);
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
        WorkflowNode node = ctx.node();
        String language = node.configString("language");
        if (!isSupportedLanguage(language)) {
            throw new NodeFailedException("NODE_CONFIG_INVALID",
                    "code 节点 language 只能是 javascript / python，实际 '" + language + "'");
        }
        String code = node.configString("code");
        if (code == null || code.isBlank()) {
            throw new NodeFailedException("NODE_CONFIG_INVALID", "code 节点缺少 code");
        }
        if (code.length() > MAX_CODE_CHARS) {
            throw new NodeFailedException("NODE_CONFIG_INVALID",
                    "code 节点代码长度必须 <= " + MAX_CODE_CHARS + " 字符，实际 " + code.length());
        }
        int timeoutSec = node.configInt("timeoutSec", DEFAULT_TIMEOUT_SEC);
        if (timeoutSec < MIN_TIMEOUT_SEC || timeoutSec > MAX_TIMEOUT_SEC) {
            throw new NodeFailedException("NODE_CONFIG_INVALID",
                    "code 节点 timeoutSec 必须在 " + MIN_TIMEOUT_SEC + ".." + MAX_TIMEOUT_SEC
                            + " 之间，实际 " + timeoutSec);
        }
        if (ctx.isCancelled()) {
            throw new NodeCancelledException("运行已取消，停止代码执行");
        }

        Map<String, Object> input = resolveInputs(node, ctx);
        String inputJson;
        try {
            inputJson = objectMapper.writeValueAsString(input);
        } catch (Exception e) {
            throw new NodeFailedException("CODE_INPUT_INVALID", "code 节点输入无法序列化为 JSON");
        }
        if (inputJson.length() > MAX_INPUT_JSON_CHARS) {
            throw new NodeFailedException("CODE_INPUT_TOO_LARGE",
                    "code 节点输入超过 " + MAX_INPUT_JSON_CHARS + " 字符上限");
        }

        String languageId = "javascript".equals(language) ? JS : PYTHON;
        requireLanguage(languageId);

        AtomicReference<Context> activeContext = new AtomicReference<>();
        Future<Object> future;
        try {
            future = codePool.submit(() -> executeInSandbox(languageId, code, inputJson, activeContext));
        } catch (RejectedExecutionException e) {
            throw new NodeFailedException("CODE_RUNTIME_UNAVAILABLE", "代码执行线程池繁忙，请稍后重试");
        }

        Object result;
        try {
            result = future.get(timeoutSec, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            cancelContext(activeContext);
            future.cancel(true);
            throw new NodeFailedException("CODE_TIMEOUT", "代码执行超过 " + timeoutSec + " 秒");
        } catch (InterruptedException e) {
            cancelContext(activeContext);
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new NodeCancelledException("代码执行等待被中断");
        } catch (CancellationException e) {
            throw new NodeCancelledException("代码执行已被取消");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof NodeFailedException failed) {
                throw failed;
            }
            if (cause instanceof CodeRuntimeUnavailableException) {
                throw new NodeFailedException("CODE_RUNTIME_UNAVAILABLE",
                        cause.getMessage() == null ? "代码运行时不可用" : cause.getMessage());
            }
            if (cause instanceof CodeEntrypointMissingException) {
                throw new NodeFailedException("CODE_ENTRYPOINT_MISSING",
                        "代码必须定义 main(input) 入口函数");
            }
            if (cause instanceof PolyglotException polyglot) {
                if (polyglot.isInternalError()) {
                    throw new NodeFailedException("CODE_RUNTIME_UNAVAILABLE",
                            "代码运行时不可用: " + safeMessage(polyglot));
                }
                if (polyglot.isHostException()) {
                    throw new NodeFailedException("CODE_EXECUTION_FAILED",
                            "代码访问宿主被拒绝: " + safeMessage(polyglot));
                }
                throw new NodeFailedException("CODE_EXECUTION_FAILED",
                        "代码执行失败: " + safeMessage(polyglot));
            }
            throw new NodeFailedException("CODE_EXECUTION_FAILED",
                    "代码执行失败: " + cause.getClass().getSimpleName());
        }
        if (ctx.isCancelled()) {
            throw new NodeCancelledException("运行已取消，丢弃代码执行结果");
        }
        ctx.variables().putNodeOutput(node.getKey(), node.getOutputVar(), result);
        return NodeOutcome.SUCCEEDED;
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
                input.put(name, NodeTemplateResolver.resolveValue(template, ctx, expressionEvaluator));
            } catch (ExpressionException e) {
                throw new NodeFailedException(e.getCode(), e.getMessage(), e);
            }
        }
        return input;
    }

    /** 沙箱内执行：创建 Context、注入 guest 原生输入、调用 main、递归转换返回值。 */
    private Object executeInSandbox(String languageId, String code, String inputJson,
                                    AtomicReference<Context> activeContext) {
        Context context;
        try {
            context = Context.newBuilder(languageId)
                    .engine(engine())
                    .allowHostAccess(HostAccess.NONE)
                    .allowHostClassLookup(name -> false)
                    .allowHostClassLoading(false)
                    .allowIO(IOAccess.NONE)
                    .allowEnvironmentAccess(EnvironmentAccess.NONE)
                    .allowNativeAccess(false)
                    .allowCreateThread(false)
                    .out(OutputStream.nullOutputStream())
                    .err(OutputStream.nullOutputStream())
                    .build();
        } catch (Throwable t) {
            throw new CodeRuntimeUnavailableException(
                    "代码运行时不可用（" + languageId + "）: " + t.getClass().getSimpleName());
        }
        activeContext.set(context);
        try {
            Value guestInput = parseGuestInput(context, languageId, inputJson);
            context.eval(languageId, code);
            Value main = context.getBindings(languageId).getMember("main");
            if (main == null || !main.canExecute()) {
                throw new CodeEntrypointMissingException();
            }
            Value value = main.execute(guestInput);
            Object converted = toJava(value);
            return normalizeJson(converted);
        } finally {
            activeContext.compareAndSet(context, null);
            try {
                context.close(true);
            } catch (Throwable ignored) {
                // close(true) 在已取消/已关闭时可能抛异常；执行结果已经由 future 传递
            }
        }
    }

    /** JSON → guest 原生对象（HostAccess.NONE 下不能把宿主 Map 直接交给 guest）。 */
    private Value parseGuestInput(Context context, String languageId, String inputJson) {
        try {
            if (JS.equals(languageId)) {
                return context.eval(JS, "JSON.parse").execute(inputJson);
            }
            Value loads = context.eval(PYTHON, "import json\njson.loads");
            return loads.execute(inputJson);
        } catch (PolyglotException e) {
            if (e.isInternalError() || e.isHostException()) {
                throw new CodeRuntimeUnavailableException(
                        "代码运行时无法初始化输入桥（" + languageId + "）: " + safeMessage(e));
            }
            throw e;
        }
    }

    /** Graal Value → 标准 Java 结构；无法表示的值按不可序列化处理，不 toString 糊弄。 */
    private Object toJava(Value value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isString()) {
            return value.asString();
        }
        if (value.isNumber()) {
            if (value.fitsInLong()) {
                return value.asLong();
            }
            double d = value.asDouble();
            if (!Double.isFinite(d)) {
                throw new NodeFailedException("CODE_OUTPUT_NOT_SERIALIZABLE",
                        "代码返回值包含 NaN/Infinity，无法序列化为 JSON");
            }
            return d;
        }
        if (value.hasArrayElements()) {
            List<Object> list = new ArrayList<>();
            long size = value.getArraySize();
            for (long i = 0; i < size; i++) {
                list.add(toJava(value.getArrayElement(i)));
            }
            return list;
        }
        if (value.hasHashEntries()) {
            Map<String, Object> map = new LinkedHashMap<>();
            Value keys = value.getHashKeysIterator();
            Value values = value.getHashValuesIterator();
            while (keys.hasIteratorNextElement()) {
                Value key = keys.getIteratorNextElement();
                Object mapKey = key.isString() ? key.asString() : String.valueOf(toJava(key));
                Object mapValue = values.hasIteratorNextElement()
                        ? toJava(values.getIteratorNextElement()) : null;
                map.put(String.valueOf(mapKey), mapValue);
            }
            return map;
        }
        if (value.hasMembers()) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (String key : value.getMemberKeys()) {
                map.put(key, toJava(value.getMember(key)));
            }
            return map;
        }
        throw new NodeFailedException("CODE_OUTPUT_NOT_SERIALIZABLE",
                "代码返回值不是 JSON 可序列化类型");
    }

    /** 用 Jackson 做最后一道 JSON 归一化：不可序列化 → 稳定错误码。 */
    private Object normalizeJson(Object value) {
        try {
            String json = objectMapper.writeValueAsString(value);
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            throw new NodeFailedException("CODE_OUTPUT_NOT_SERIALIZABLE",
                    "代码返回值无法序列化为 JSON");
        }
    }

    private void requireLanguage(String languageId) {
        Engine current;
        try {
            current = engine();
        } catch (Throwable t) {
            throw new NodeFailedException("CODE_RUNTIME_UNAVAILABLE",
                    "代码运行时初始化失败: " + t.getClass().getSimpleName());
        }
        if (!current.getLanguages().containsKey(languageId)) {
            throw new NodeFailedException("CODE_RUNTIME_UNAVAILABLE",
                    "代码运行时不可用（缺少语言 " + languageId + "）");
        }
    }

    private Engine engine() {
        Engine current = engine;
        if (current == null) {
            synchronized (this) {
                current = engine;
                if (current == null) {
                    current = Engine.newBuilder()
                            .option("engine.WarnInterpreterOnly", "false")
                            .build();
                    engine = current;
                }
            }
        }
        return current;
    }

    private void cancelContext(AtomicReference<Context> activeContext) {
        Context context = activeContext.getAndSet(null);
        if (context != null) {
            try {
                // 强制关闭正在执行的 context：这是 GraalVM 官方取消运行中 guest 代码的方式
                context.close(true);
            } catch (Throwable ignored) {
                // 取消是尽力而为；future.cancel 仍会打断等待
            }
        }
    }

    private String safeMessage(PolyglotException e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = e.getClass().getSimpleName();
        }
        if (message.length() > 300) {
            message = message.substring(0, 300);
        }
        return sensitiveDataFilter.filter(message);
    }

    private static boolean isSupportedLanguage(String language) {
        return "javascript".equals(language) || "python".equals(language);
    }

    @Override
    @PreDestroy
    public void close() {
        codePool.shutdownNow();
        Engine current = engine;
        if (current != null) {
            try {
                current.close(true);
            } catch (Throwable ignored) {
                // 关闭失败不影响进程退出
            }
        }
    }

    private static final class CodeRuntimeUnavailableException extends RuntimeException {
        CodeRuntimeUnavailableException(String message) {
            super(message);
        }
    }

    private static final class CodeEntrypointMissingException extends RuntimeException {
    }
}
