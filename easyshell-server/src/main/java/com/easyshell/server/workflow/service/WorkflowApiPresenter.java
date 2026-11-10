package com.easyshell.server.workflow.service;

import com.easyshell.server.ai.security.SensitiveDataFilter;
import com.easyshell.server.model.entity.Workflow;
import com.easyshell.server.model.entity.WorkflowApproval;
import com.easyshell.server.model.entity.WorkflowNodeRun;
import com.easyshell.server.model.entity.WorkflowRun;
import com.easyshell.server.model.vo.WorkflowApprovalVO;
import com.easyshell.server.model.vo.WorkflowNodeRunVO;
import com.easyshell.server.model.vo.WorkflowRunDetailVO;
import com.easyshell.server.model.vo.WorkflowRunVO;
import com.easyshell.server.repository.WorkflowRepository;
import com.easyshell.server.repository.WorkflowRunRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流 REST 的<b>出站装配</b>：实体 → VO。
 *
 * <p>为什么集中在这一类：run / nodeRun / approval 三张表的落库列是文本形态
 * （{@code input_json}、{@code variables_json}、{@code current_nodes}、{@code detail_json}），
 * 而 API 契约要的是对象与数组。解析逻辑写一遍就够了——写在 Controller 里会出现四份
 * 拷贝，写在前端就意味着把存储形态泄漏给浏览器。这里同时是 §6.7.3 出口 1/2 的落点：
 * {@code workflow_run.input_json} / {@code variables_json} <b>出站前</b>过
 * {@link SensitiveDataFilter}（掩码统一为 {@code ***}，禁止第二套掩码实现）。</p>
 *
 * <p>无状态、无事务：只做形态翻译与脱敏，不写库。</p>
 */
@Slf4j
@Component
public class WorkflowApiPresenter {

    private final ObjectMapper objectMapper;
    private final SensitiveDataFilter sensitiveDataFilter;
    private final WorkflowRepository workflowRepository;
    private final WorkflowRunRepository runRepository;

    public WorkflowApiPresenter(ObjectMapper objectMapper, SensitiveDataFilter sensitiveDataFilter,
                                WorkflowRepository workflowRepository,
                                WorkflowRunRepository runRepository) {
        this.objectMapper = objectMapper;
        this.sensitiveDataFilter = sensitiveDataFilter;
        this.workflowRepository = workflowRepository;
        this.runRepository = runRepository;
    }

    // ------------------------------------------------------------------
    // run / nodeRun
    // ------------------------------------------------------------------

    public WorkflowRunVO run(WorkflowRun run) {
        if (run == null) {
            return null;
        }
        return WorkflowRunVO.builder()
                .id(run.getId())
                .workflowId(run.getWorkflowId())
                .versionId(run.getVersionId())
                .versionNo(run.getVersionNo())
                .triggerType(run.getTriggerType())
                .status(run.getStatus())
                .input(readMap(mask(run.getInputJson())))
                .output(readMap(mask(run.getOutputJson())))
                .errorMessage(mask(run.getErrorMessage()))
                .variables(readMap(mask(run.getVariablesJson())))
                .variablesTruncated(Boolean.TRUE.equals(run.getVariablesTruncated()))
                .currentNodes(readKeys(run.getCurrentNodes()))
                .cancelRequested(Boolean.TRUE.equals(run.getCancelRequested()))
                .startedBy(run.getStartedBy())
                .createdAt(run.getCreatedAt())
                .startedAt(run.getStartedAt())
                .finishedAt(run.getFinishedAt())
                .durationMs(run.getDurationMs())
                .build();
    }

    public WorkflowNodeRunVO nodeRun(WorkflowNodeRun nodeRun) {
        if (nodeRun == null) {
            return null;
        }
        return WorkflowNodeRunVO.builder()
                .id(nodeRun.getId())
                .runId(nodeRun.getRunId())
                .nodeKey(nodeRun.getNodeKey())
                .nodeType(nodeRun.getNodeType())
                .attempt(nodeRun.getAttempt())
                .status(nodeRun.getStatus())
                .taskId(nodeRun.getTaskId())
                // MF-13：input_json 存的是渲染后的 parameters Map，值可能就是凭据（PAT 等）。
                // 落库保留原文供审计，但出站前必须与 run 级快照同样脱敏（§6.7.3 出口 1/2，
                // 复用同一个 SensitiveDataFilter，不写第二套掩码）。
                .input(readMap(mask(nodeRun.getInputJson())))
                .output(readMap(mask(nodeRun.getOutputJson())))
                .errorMessage(mask(nodeRun.getErrorMessage()))
                .warningMessage(nodeRun.getWarningMessage())
                .createdAt(nodeRun.getCreatedAt())
                .startedAt(nodeRun.getStartedAt())
                .finishedAt(nodeRun.getFinishedAt())
                .durationMs(nodeRun.getDurationMs())
                .build();
    }

    public List<WorkflowNodeRunVO> nodeRuns(List<WorkflowNodeRun> nodeRuns) {
        if (nodeRuns == null || nodeRuns.isEmpty()) {
            return List.of();
        }
        return nodeRuns.stream().map(this::nodeRun).toList();
    }

    public WorkflowRunDetailVO runDetail(WorkflowRun run, List<WorkflowNodeRun> nodeRuns) {
        return new WorkflowRunDetailVO(run(run), nodeRuns(nodeRuns));
    }

    // ------------------------------------------------------------------
    // approval
    // ------------------------------------------------------------------

    public WorkflowApprovalVO approval(WorkflowApproval approval) {
        if (approval == null) {
            return null;
        }
        Long workflowId = null;
        String workflowName = null;
        // run → workflow 反查：审批中心列表要显示"哪个工作流"，实体上没有这个维度
        workflowId = runRepository.findById(approval.getRunId()).map(WorkflowRun::getWorkflowId).orElse(null);
        if (workflowId != null) {
            workflowName = workflowRepository.findById(workflowId).map(Workflow::getName).orElse(null);
        }
        return WorkflowApprovalVO.builder()
                .id(approval.getId())
                .runId(approval.getRunId())
                .workflowId(workflowId)
                .workflowName(workflowName)
                .nodeKey(approval.getNodeKey())
                .nodeType(approval.getNodeType())
                .title(approval.getTitle())
                .detail(readMap(approval.getDetailJson()))
                .riskLevel(approval.getRiskLevel())
                .riskMatches(approval.getRiskMatches())
                .status(approval.getStatus())
                .approverRole(approval.getApproverRole())
                .decidedBy(approval.getDecidedBy())
                .decidedAt(approval.getDecidedAt())
                .reason(approval.getDecisionComment())
                .expiresAt(approval.getExpiresAt())
                .createdAt(approval.getCreatedAt())
                .build();
    }

    public List<WorkflowApprovalVO> approvals(List<WorkflowApproval> approvals) {
        if (approvals == null || approvals.isEmpty()) {
            return List.of();
        }
        return approvals.stream().map(this::approval).toList();
    }

    // ------------------------------------------------------------------
    // 内部：存储形态 → API 形态
    // ------------------------------------------------------------------

    /** §6.7.3 出口 1/2：run 级快照出站前脱敏（掩码统一 ***） */
    private String mask(String json) {
        return json == null ? null : sensitiveDataFilter.filter(json);
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            // 快照列写坏不应让整个详情 500：按"不存在"处理（G-10 同精神）。
            // 不记录异常 message：Jackson 可能回显 payload 片段（含凭据）
            log.warn("Workflow snapshot column is not a JSON object（内容已隐去）");
            return null;
        }
    }

    /** current_nodes 快照列是 JSON 数组文本（"[]" / "[\"n1\",\"n2\"]"） */
    private List<String> readKeys(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<ArrayList<String>>() {});
        } catch (Exception e) {
            log.warn("Workflow current_nodes column is not a JSON array（内容已隐去）");
            return List.of();
        }
    }
}
