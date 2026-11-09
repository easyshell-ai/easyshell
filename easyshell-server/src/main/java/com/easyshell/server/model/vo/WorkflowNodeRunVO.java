package com.easyshell.server.model.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 节点运行出站视图（§6.1 GET /runs/{runId} 的 {@code nodeRuns[]} 元素）。
 *
 * <p>与 {@link WorkflowRunVO} 同一个理由：{@code inputJson}/{@code outputJson} 是落库文本列，
 * {@code outputSha256}/{@code previewSha256}/{@code rawParams}/{@code artifactIds}/
 * {@code agentVersions}/{@code cancelDelivered} 是审计与内部字段。监控页要的是
 * "这个节点拿到了什么参数、产出什么、耗时多久、关联哪个 Task"（设计 §3.13 演示⑧），
 * 因此这里只透出这些，哈希与内部句柄留在实体里。</p>
 */
@Data
@Builder
public class WorkflowNodeRunVO {

    private String id;

    private String runId;

    private String nodeKey;

    /** script-exec / condition / notify / approval / wait */
    private String nodeType;

    /** 从 1 开始，每次重试 +1 */
    private Integer attempt;

    private String status;

    /** 软关联 task.id（监控页 Task 跳转） */
    private String taskId;

    /** 渲染后的 parameters Map（input_json 解析，不含渲染后脚本全文，MF-13） */
    private Map<String, Object> input;

    /** 节点输出（output_json 解析；≤8192B 全文或 preview 对象） */
    private Map<String, Object> output;

    private String errorMessage;

    private String warningMessage;

    private LocalDateTime createdAt;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private Long durationMs;
}
