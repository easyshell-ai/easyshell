package com.easyshell.server.workflow.engine;

import com.easyshell.server.workflow.expression.ExpressionEvaluator;
import com.easyshell.server.workflow.expression.VariableResolver;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 变量存储 —— M1 三条通道里<b>实际存在的一条</b>（标量通道）+ artifact 引用通道的降级形态。
 *
 * <p><b>通道边界（§2.1 / §2.10）</b>：</p>
 * <ul>
 *   <li><b>标量通道</b>：单值 ≤ 8192 UTF-8 字节全文进内存 Map，波末快照落
 *       {@code workflow_run.variables_json}；8192 &lt; size ≤ 262144 → 放 preview +
 *       {@code truncated=true} + warning；&gt; 262144 → 只放 preview + warning，
 *       全文由 task 日志 WS 与 {@code task_id} 承载；</li>
 *   <li><b>artifact 引用通道</b>：{@code workflow_artifact} 表在 M2（X15），M1 的临时方案就是
 *       上面的 preview 对象 + {@code workflow_node_run.output_json}/{@code output_sha256}，
 *       <b>不建新表、不落句柄</b>——{@code artifact_ids} 列 M1 恒空；</li>
 *   <li><b>secret 只写通道</b>：M1 明确不做（X13，agent payload 只有 script 一个字段，
 *       任何"secret 安全注入"都是装饰）。</li>
 * </ul>
 *
 * <p><b>并发写冲突</b>：发布期强制"同一 outputVar 只允许一个写者"（§2.8 fan-in M1 限制），
 * 因此正常运行不存在同键并发写。防御性兜底：后写者覆盖先写者并记 warning
 * （M2 的 last-write-wins / array-append / join 三策略尚未实现）。</p>
 *
 * <p><b>作用域</b>：{@code run.input.*} / {@code run.*} 元信息只读；
 * {@code nodes.<key>.output} 仅该节点的<b>拓扑后继</b>可读（发布期静态校验引用集合 ⊆ 祖先集合，
 * 运行期由 {@code NodeContext} 只注入祖先输出保证）。<b>没有全局变量通道</b>（v1 的 vars.* 已删除）。</p>
 */
public class VariableStore implements VariableResolver {

    // A pre-created public handle stays stable while the complete recovered state is
    // published in one volatile write. Candidate stores are never themselves handles.
    private volatile VariableStore initializedState;

    boolean isInitialized() {
        return initializedState != null;
    }

    void publishInitialized(VariableStore candidate) {
        if (candidate == this || candidate.initializedState != null) {
            throw new IllegalArgumentException("invalid initialization candidate");
        }
        initializedState = candidate;
    }

    /** 标量内联阈值（UTF-8 字节，§2.10） */
    public static final long INLINE_THRESHOLD = 8192L;

    /** 单值硬上限（超过只放 preview） */
    public static final long MAX_VALUE = 262144L;

    /** variables_json 全量快照上限 */
    public static final long SNAPSHOT_LIMIT = 524288L;

    /** preview 组成：前 6KB + 尾 2KB */
    private static final int PREVIEW_HEAD = 6144;
    private static final int PREVIEW_TAIL = 2048;

    /** End 节点专用运行输出通道上限（UTF-8 字节）；超过时写合法对象并标记截断 */
    public static final int RUN_OUTPUT_MAX_BYTES = 65536;

    private final Map<String, Object> scalars = new ConcurrentHashMap<>();
    private final Map<String, StoredOutput> nodeOutputs = new ConcurrentHashMap<>();
    private final Map<String, String> outputVarWriters = new ConcurrentHashMap<>();
    private final Map<String, String> nodeStatuses = new ConcurrentHashMap<>();
    private final Map<String, Long> nodeDurations = new ConcurrentHashMap<>();
    private final Map<String, Integer> nodeAttempts = new ConcurrentHashMap<>();
    private final Map<String, Object> runMetadata = new ConcurrentHashMap<>();
    private final List<String> warnings;
    private final Object warningLock;

    /**
     * End 节点的完整运行输出。普通 node 输出仍走 8192B preview 通道；这份专用通道只给
     * {@code workflow_run.output_json} 用，避免大 End 输出被 preview 破坏成非 JSON。
     */
    private final java.util.concurrent.atomic.AtomicReference<Object> runOutput =
            new java.util.concurrent.atomic.AtomicReference<>();

    public VariableStore() {
        this.warnings = new ArrayList<>();
        this.warningLock = new Object();
    }

    /** 节点输出的落库形态 */
    public record StoredOutput(String nodeKey, String json, Object value, boolean truncated,
                               long sizeBytes, String sha256, String previewSha256) {
    }

    /** 波末快照 */
    public record Snapshot(String json, boolean truncated) {
    }

    // ==================================================================
    // 写入
    // ==================================================================

    public void putInput(String name, Object value) {
        VariableStore state = initializedState;
        if (state != null) { state.putInput(name, value); return; }
        if (value != null) {
            scalars.put("run.input." + name, value);
        }
    }

    public void putRunMetadata(String key, Object value) {
        VariableStore state = initializedState;
        if (state != null) { state.putRunMetadata(key, value); return; }
        if (key != null && value != null) {
            runMetadata.put(key, value);
        }
    }

    /**
     * 设置 End 运行输出（专用于 workflow_run.output_json）。
     *
     * <p>与 {@link #putNodeOutput} 的 8KB preview 不同，这里保留完整对象；超过
     * {@link #RUN_OUTPUT_MAX_BYTES} 时写入一个合法对象并记 warning，保证 API 永远能解析成对象。</p>
     */
    public void setRunOutput(Object value) {
        VariableStore state = initializedState;
        if (state != null) { state.setRunOutput(value); return; }
        if (value == null) {
            return;
        }
        String json = toJson(value);
        long sizeBytes = json.getBytes(StandardCharsets.UTF_8).length;
        if (sizeBytes > RUN_OUTPUT_MAX_BYTES) {
            Map<String, Object> capped = new LinkedHashMap<>();
            capped.put("_truncated", true);
            capped.put("message", "运行输出超过 " + RUN_OUTPUT_MAX_BYTES + " 字节上限，已截断");
            capped.put("preview", json.substring(0, Math.min(json.length(), RUN_OUTPUT_MAX_BYTES)));
            addWarning("运行输出超过 " + RUN_OUTPUT_MAX_BYTES + " 字节，已截断 preview");
            runOutput.set(capped);
            return;
        }
        runOutput.set(value);
    }

    public Object runOutput() {
        VariableStore state = initializedState;
        if (state != null) return state.runOutput();
        return runOutput.get();
    }

    /** 记录节点输出（标量/引用通道的唯一入口，负责截断与指纹） */
    public StoredOutput putNodeOutput(String nodeKey, String outputVar, Object value) {
        VariableStore state = initializedState;
        if (state != null) return state.putNodeOutput(nodeKey, outputVar, value);
        String json = toJson(value);
        // 计量口径（§2.10）：标量字符串按原文 UTF-8 字节数，结构化值按 JSON 编码字节数
        long sizeBytes = value instanceof String text
                ? text.getBytes(StandardCharsets.UTF_8).length
                : json.getBytes(StandardCharsets.UTF_8).length;
        boolean truncated = sizeBytes > INLINE_THRESHOLD;
        String storedJson = json;
        Object storedValue = value;
        if (truncated) {
            // 字符串输出按原文取 preview（前 6KB + 尾 2KB），结构化输出按 JSON 编码取——
            // preview 本身不是合法 JSON 也不要紧：它只是给人看的摘要，下游要读字段得走
            // M2 的 artifact 读取端点（X15）
            String preview = value instanceof String text ? buildPreview(text) : buildPreview(json);
            storedJson = preview;
            storedValue = preview;
            addWarning("节点 " + nodeKey + " 输出 " + sizeBytes + "B 超过内联阈值 "
                    + INLINE_THRESHOLD + "B，已截断为 preview（M1 无 artifact 表，全文见 task 日志）");
        }
        if (sizeBytes > MAX_VALUE) {
            addWarning("节点 " + nodeKey + " 输出 " + sizeBytes + "B 超过单值硬上限 "
                    + MAX_VALUE + "B，仅保留 preview");
        }
        StoredOutput output = new StoredOutput(nodeKey, storedJson, storedValue, truncated, sizeBytes,
                sha256(json.getBytes(StandardCharsets.UTF_8)),
                truncated ? sha256(storedJson.getBytes(StandardCharsets.UTF_8)) : null);
        nodeOutputs.put(nodeKey, output);
        // ConcurrentHashMap 不接受 null 值：null 输出等价于"不存在"（§2.2 ① 上游失败 ⇒ output 不存在），
        // 因此直接不写入，读取方拿到 empty 后按 fail-closed 处置
        if (storedValue != null) {
            scalars.put("nodes." + nodeKey + ".output", storedValue);
        }
        if (outputVar != null && !outputVar.isBlank()) {
            String previous = outputVarWriters.put(outputVar, nodeKey);
            if (previous != null && !previous.equals(nodeKey)) {
                addWarning("outputVar '" + outputVar + "' 出现并发写者（" + previous + " / " + nodeKey
                        + "），M1 取后写者；fan-in 合并策略在 M2");
            }
            if (storedValue != null) {
                scalars.put(outputVar, storedValue);
            }
        }
        return output;
    }

    public void putNodeStatus(String nodeKey, String status, Long durationMs, Integer attempt) {
        VariableStore state = initializedState;
        if (state != null) { state.putNodeStatus(nodeKey, status, durationMs, attempt); return; }
        if (status != null) {
            nodeStatuses.put(nodeKey, status);
        }
        if (durationMs != null) {
            nodeDurations.put(nodeKey, durationMs);
        }
        if (attempt != null) {
            nodeAttempts.put(nodeKey, attempt);
        }
    }

    public StoredOutput nodeOutput(String nodeKey) {
        VariableStore state = initializedState;
        if (state != null) return state.nodeOutput(nodeKey);
        return nodeOutputs.get(nodeKey);
    }

    // ==================================================================
    // 读取（VariableResolver）
    // ==================================================================

    @Override
    public Optional<Object> resolve(String path) {
        VariableStore state = initializedState;
        if (state != null) return state.resolve(path);
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        String normalized = ExpressionEvaluator.normalizePath(path);
        String[] segments = normalized.split("\\.");
        if (segments.length == 0) {
            return Optional.empty();
        }
        // run.input.<name>
        if ("run".equals(segments[0])) {
            if (segments.length >= 3 && "input".equals(segments[1])) {
                return Optional.ofNullable(scalars.get("run.input." + segments[2]));
            }
            if (segments.length == 2) {
                return Optional.ofNullable(runMetadata.get("run." + segments[1]));
            }
            return Optional.empty();
        }
        // nodes.<key>.output[.<path>] / nodes.<key>.status|durationMs|attempt
        if ("nodes".equals(segments[0]) && segments.length >= 3) {
            String nodeKey = segments[1];
            String field = segments[2];
            if ("output".equals(field)) {
                StoredOutput output = nodeOutputs.get(nodeKey);
                if (output == null) {
                    return Optional.empty();
                }
                if (segments.length == 3) {
                    return Optional.ofNullable(output.value());
                }
                String rest = String.join(".", List.of(segments).subList(3, segments.length));
                return Optional.ofNullable(ExpressionEvaluator.readJsonPath(output.json(), rest));
            }
            if ("status".equals(field) && segments.length == 3) {
                return Optional.ofNullable(nodeStatuses.get(nodeKey));
            }
            if ("durationMs".equals(field) && segments.length == 3) {
                return Optional.ofNullable(nodeDurations.get(nodeKey));
            }
            if ("attempt".equals(field) && segments.length == 3) {
                return Optional.ofNullable(nodeAttempts.get(nodeKey));
            }
            return Optional.empty();
        }
        // outputVar 别名（与 nodes.<key>.output 始终等价，MF-11）
        if (segments.length >= 1) {
            String alias = segments[0];
            String nodeKey = outputVarWriters.get(alias);
            if (nodeKey != null) {
                StoredOutput output = nodeOutputs.get(nodeKey);
                if (output == null) {
                    return Optional.empty();
                }
                if (segments.length == 1) {
                    return Optional.ofNullable(output.value());
                }
                String rest = String.join(".", List.of(segments).subList(1, segments.length));
                return Optional.ofNullable(ExpressionEvaluator.readJsonPath(output.json(), rest));
            }
        }
        return Optional.empty();
    }

    /** 运行期权限：节点只能读其拓扑前驱的输出——由 {@link #createSnapshot} 的快照隔离保证 */
    // ==================================================================
    // 快照
    // ==================================================================

    /**
     * 节点开始执行前的不可变快照（§2.2 ③）：只含该节点拓扑祖先的输出，
     * 于是"读到自己/同波节点/非祖先节点"在运行期结构上不可能。
     */
    public VariableStore createSnapshot(java.util.Set<String> ancestorKeys) {
        VariableStore state = initializedState;
        if (state != null) return state.createSnapshot(ancestorKeys);
        VariableStore snapshot = new VariableStore();
        this.scalars.forEach((key, value) -> {
            if (key.startsWith("run.input.") || key.startsWith("nodes.")) {
                String nodeKey = key.startsWith("nodes.") ? key.substring(6).split("\\.")[0] : null;
                if (nodeKey == null || ancestorKeys.contains(nodeKey)) {
                    snapshot.scalars.put(key, value);
                }
            } else {
                // outputVar 别名：写者是祖先才带过去
                String writer = outputVarWriters.get(key);
                if (writer == null || ancestorKeys.contains(writer)) {
                    snapshot.scalars.put(key, value);
                }
            }
        });
        this.nodeOutputs.forEach((key, value) -> {
            if (ancestorKeys.contains(key)) {
                snapshot.nodeOutputs.put(key, value);
            }
        });
        this.outputVarWriters.forEach((alias, writer) -> {
            if (ancestorKeys.contains(writer)) {
                snapshot.outputVarWriters.put(alias, writer);
            }
        });
        this.nodeStatuses.forEach(snapshot.nodeStatuses::put);
        this.nodeDurations.forEach(snapshot.nodeDurations::put);
        this.nodeAttempts.forEach(snapshot.nodeAttempts::put);
        snapshot.runMetadata.putAll(this.runMetadata);
        return snapshot;
    }

    /** 仅在成功 attempt 的终态事务提交后调用；不重新序列化 preview。 */
    synchronized void mergeAttempt(VariableStore attempt, String nodeKey) {
        VariableStore state = initializedState;
        if (state != null) { state.mergeAttempt(attempt, nodeKey); return; }
        StoredOutput output = attempt.nodeOutputs.get(nodeKey);
        if (output != null) {
            nodeOutputs.put(nodeKey, output);
            if (output.value() != null) {
                scalars.put("nodes." + nodeKey + ".output", output.value());
            }
            attempt.outputVarWriters.forEach((alias, writer) -> {
                if (nodeKey.equals(writer)) {
                    String previous = outputVarWriters.put(alias, writer);
                    if (previous != null && !previous.equals(writer)) {
                        addWarning("outputVar '" + alias + "' 出现并发写者（" + previous + " / " + writer
                                + "），M1 取后写者；fan-in 合并策略在 M2");
                    }
                    if (output.value() != null) {
                        scalars.put(alias, output.value());
                    }
                }
            });
        }
        if (attempt.runOutput() != null) {
            runOutput.set(attempt.runOutput());
        }
    }

    /** 波末全量快照（落 workflow_run.variables_json） */
    public Snapshot snapshot() {
        VariableStore state = initializedState;
        if (state != null) return state.snapshot();
        Map<String, Object> all = new LinkedHashMap<>();
        all.putAll(runMetadata);
        scalars.forEach((key, value) -> all.put(key, value));
        String json = toJson(all);
        if (json.getBytes(StandardCharsets.UTF_8).length > SNAPSHOT_LIMIT) {
            // G-10：被截断的变量按"不存在"处理；保留最近写入的 20 个标量
            Map<String, Object> trimmed = new LinkedHashMap<>();
            trimmed.putAll(runMetadata);
            List<Map.Entry<String, Object>> entries = new ArrayList<>(scalars.entrySet());
            int from = Math.max(0, entries.size() - 20);
            for (Map.Entry<String, Object> entry : entries.subList(from, entries.size())) {
                trimmed.put(entry.getKey(), entry.getValue());
            }
            addWarning("变量快照超过 " + SNAPSHOT_LIMIT + "B，已截断为最近 20 个标量");
            return new Snapshot(toJson(trimmed), true);
        }
        return new Snapshot(json, false);
    }

    // ==================================================================
    // warning
    // ==================================================================

    public void addWarning(String warning) {
        VariableStore state = initializedState;
        if (state != null) { state.addWarning(warning); return; }
        synchronized (warningLock) {
            if (warnings.size() < 32) {
                warnings.add(warning);
            }
        }
    }

    /** 取出并清空 warning（节点结束时合并进 node_run.warning_message） */
    public List<String> drainWarnings() {
        VariableStore state = initializedState;
        if (state != null) return state.drainWarnings();
        synchronized (warningLock) {
            List<String> copy = new ArrayList<>(warnings);
            warnings.clear();
            return copy;
        }
    }

    // ==================================================================
    // 内部
    // ==================================================================

    private String buildPreview(String json) {
        if (json.length() <= PREVIEW_HEAD + PREVIEW_TAIL) {
            return json;
        }
        return json.substring(0, PREVIEW_HEAD) + json.substring(json.length() - PREVIEW_TAIL);
    }

    private String toJson(Object value) {
        try {
            return new ObjectMapper().writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }
}
