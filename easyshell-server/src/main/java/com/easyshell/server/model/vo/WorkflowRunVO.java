package com.easyshell.server.model.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 运行出站视图（§6.1 POST /{id}/runs、GET /runs、GET /runs/{runId}、
 * POST /runs/{runId}/cancel 的 {@code run} 部分）。
 *
 * <p><b>为什么不直接序列化实体</b>：实体上落库用的是 {@code *_json} 文本列与逗号/JSON
 * 快照（{@code inputJson}、{@code variablesJson}、{@code currentNodes}），还有
 * {@code ownerInstance}、{@code heartbeatAt}、{@code riskSnapshotJson}、{@code idempotencyKey}
 * 这些内部字段。把它们原样透出去，前端拿到的 {@code input} 永远是 undefined、
 * {@code currentNodes} 是字符串却要被当数组 {@code join}（直接抛 TypeError），
 * 变量面板永远是空的。本 VO 把存储形态翻译成 API 形态：JSON 列解析成对象、
 * {@code currentNodes} 解析成字符串数组、内部字段不外泄。</p>
 *
 * <p>{@code input}/{@code variables} 在解析前过 {@code SensitiveDataFilter}
 * （§6.7.3 出口 1/2：出站前掩码，统一掩码为 {@code ***}，禁止第二套掩码实现）。</p>
 */
@Data
@Builder
public class WorkflowRunVO {

    private String id;

    private Long workflowId;

    private Long versionId;

    private Integer versionNo;

    /** manual / scheduled(M2) / replay(M3) */
    private String triggerType;

    private String status;

    /** 触发入参全量快照（input_json 解析，出站前脱敏） */
    private Map<String, Object> input;

    /** run 级输出（output_json 解析） */
    private Map<String, Object> output;

    private String errorMessage;

    /** 波末 checkpoint 的标量变量全量快照（variables_json 解析，出站前脱敏） */
    private Map<String, Object> variables;

    /** 变量快照被截断（G-10：被截断的变量按不存在处理） */
    private Boolean variablesTruncated;

    /** 当前波正在执行的 nodeKey 列表（快照列解析成数组） */
    private List<String> currentNodes;

    /** 取消请求标记（B10：下一检查点传播） */
    private Boolean cancelRequested;

    private Long startedBy;

    private LocalDateTime createdAt;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private Long durationMs;
}
