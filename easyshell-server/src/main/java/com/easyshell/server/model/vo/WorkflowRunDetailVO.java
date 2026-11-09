package com.easyshell.server.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 运行详情（§6.1 GET /runs/{runId}：run + 全部 nodeRun）。
 *
 * <p>两者都是出站 VO（{@link WorkflowRunVO} / {@link WorkflowNodeRunVO}）而不是实体：
 * 实体上的 {@code *_json} 文本列与内部字段不外泄，见各 VO 的说明。装配集中在
 * {@code WorkflowApiPresenter}，避免每个端点各写一遍解析。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowRunDetailVO {

    private WorkflowRunVO run;

    private List<WorkflowNodeRunVO> nodeRuns;
}
