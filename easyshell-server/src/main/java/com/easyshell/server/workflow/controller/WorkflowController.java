package com.easyshell.server.workflow.controller;

import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.common.result.R;
import com.easyshell.server.model.dto.WorkflowApprovalDecisionRequest;
import com.easyshell.server.model.dto.WorkflowCreateRequest;
import com.easyshell.server.model.dto.WorkflowDraftSaveRequest;
import com.easyshell.server.model.dto.WorkflowPublishRequest;
import com.easyshell.server.model.dto.WorkflowRunTriggerRequest;
import com.easyshell.server.model.dto.WorkflowUpdateRequest;
import com.easyshell.server.model.dto.WorkflowValidateRequest;
import com.easyshell.server.model.entity.Workflow;
import com.easyshell.server.model.entity.WorkflowApproval;
import com.easyshell.server.model.entity.WorkflowRun;
import com.easyshell.server.model.entity.WorkflowVersion;
import com.easyshell.server.model.vo.WorkflowDetailVO;
import com.easyshell.server.model.vo.WorkflowRunDetailVO;
import com.easyshell.server.model.vo.WorkflowTestRunVO;
import com.easyshell.server.model.vo.WorkflowValidateResultVO;
import com.easyshell.server.model.vo.WorkflowVersionDetailVO;
import com.easyshell.server.model.vo.WorkflowVersionVO;
import com.easyshell.server.repository.WorkflowApprovalRepository;
import com.easyshell.server.repository.WorkflowNodeRunRepository;
import com.easyshell.server.repository.WorkflowRunRepository;
import com.easyshell.server.security.AuditUsers;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.workflow.approval.WorkflowResumeService;
import com.easyshell.server.workflow.service.WorkflowApiPresenter;
import com.easyshell.server.workflow.service.WorkflowDefinitionService;
import com.easyshell.server.workflow.service.WorkflowRunService;
import com.easyshell.server.workflow.service.WorkflowTestRunService;
import com.easyshell.server.workflow.service.WorkflowCodeSampleService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.io.IOException;
import java.util.List;

/**
 * 工作流引擎 REST API（18 个端点，其中 14 个带路径变量）。
 *
 * <p><b>权限注解与数据级校验一一对应</b>（硬规则，缺一即漏洞）：方法级注解管"角色能不能
 * 碰这类操作"，{@link WorkflowAccess} 管"这个 id 是不是他的"。带路径变量的 14 个端点
 * 全部经过 {@link WorkflowAccess} 的三个函数，没有第二份内联实现（反面教材：
 * {@code TaskController.java:203-211}）。</p>
 *
 * <p>M1 <b>不做</b>的端点（设计明示）：retry(M2)、replay(M3)、/draft(M3)、/templates(M3)、
 * /{id}/triggers(M2)、webhook(M3)、单节点 test-run 的选项1裁剪版（业主已选选项3，
 * 故本类实现它）、以及 <b>POST /runs（永久删除，N7-d）</b>——触发运行的唯一路径是
 * {@code POST /{id}/runs}，它过 {@code requireWorkflowOwner}。</p>
 *
 * <p><b>响应形状不许在这里各自演进</b>：实体 → VO 的翻译（JSON 列解析、出站脱敏、内部字段
 * 收敛）集中在 {@link WorkflowApiPresenter}；每个端点的字段名与嵌套层级由
 * {@code WorkflowApiContractTest} 锁死，改 VO 先看那个测试会不会红。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/workflow")
public class WorkflowController {

    private static final int MAX_PAGE_SIZE = 200;

    private final WorkflowDefinitionService definitionService;
    private final WorkflowRunService runService;
    private final WorkflowTestRunService testRunService;
    private final WorkflowResumeService resumeService;
    private final WorkflowAccess access;
    private final WorkflowApiPresenter presenter;
    private final WorkflowRunRepository runRepository;
    private final WorkflowNodeRunRepository nodeRunRepository;
    private final WorkflowApprovalRepository approvalRepository;
    private final WorkflowCodeSampleService codeSampleService;
    private final AuditLogService auditLogService;

    public WorkflowController(WorkflowDefinitionService definitionService,
                              WorkflowRunService runService,
                              WorkflowTestRunService testRunService,
                              WorkflowResumeService resumeService,
                              WorkflowAccess access,
                              WorkflowApiPresenter presenter,
                              WorkflowRunRepository runRepository,
                              WorkflowNodeRunRepository nodeRunRepository,
                              WorkflowApprovalRepository approvalRepository,
                              WorkflowCodeSampleService codeSampleService,
                              AuditLogService auditLogService) {
        this.definitionService = definitionService;
        this.runService = runService;
        this.testRunService = testRunService;
        this.resumeService = resumeService;
        this.access = access;
        this.presenter = presenter;
        this.runRepository = runRepository;
        this.nodeRunRepository = nodeRunRepository;
        this.approvalRepository = approvalRepository;
        this.codeSampleService = codeSampleService;
        this.auditLogService = auditLogService;
    }

    // ==================================================================
    // ① GET /page —— 分页（非 admin 只返回 created_by=本人 或 visibility=public）
    // ==================================================================

    @GetMapping("/page")
    public R<Page<Workflow>> page(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication auth) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0),
                Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        Long userId = (Long) auth.getPrincipal();
        if (WorkflowAccess.isAdmin(auth)) {
            return R.ok(definitionService.pageForAdmin(status, keyword, category, pageable));
        }
        return R.ok(definitionService.pageForUser(status, keyword, category, userId, pageable));
    }

    // ==================================================================
    // ② POST / —— 新建草稿
    // ==================================================================

    @PostMapping
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN','SUPER_ADMIN')")
    public R<Workflow> create(@Valid @RequestBody WorkflowCreateRequest request, Authentication auth,
                              HttpServletRequest httpRequest) {
        Workflow workflow = definitionService.create(request, (Long) auth.getPrincipal(), auth,
                httpRequest.getRemoteAddr());
        return R.ok(workflow);
    }

    // ==================================================================
    // ③④⑤ GET/PUT/DELETE /{id} —— 详情 / 改元信息 / 软删
    // ==================================================================

    @GetMapping("/{id}")
    public R<WorkflowDetailVO> detail(@PathVariable Long id, Authentication auth) {
        access.requireWorkflowOwner(id, auth);
        return R.ok(definitionService.detail(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN','SUPER_ADMIN')")
    public R<Workflow> update(@PathVariable Long id, @Valid @RequestBody WorkflowUpdateRequest request,
                              Authentication auth, HttpServletRequest httpRequest) {
        access.requireWorkflowOwner(id, auth);
        Workflow workflow = definitionService.updateMeta(id, request, (Long) auth.getPrincipal(), auth,
                httpRequest.getRemoteAddr());
        return R.ok(workflow);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN','SUPER_ADMIN')")
    public R<Workflow> delete(@PathVariable Long id, Authentication auth, HttpServletRequest httpRequest) {
        access.requireWorkflowOwner(id, auth);
        Workflow workflow = definitionService.archive(id, (Long) auth.getPrincipal(), auth,
                httpRequest.getRemoteAddr());
        return R.ok(workflow);
    }

    // ==================================================================
    // ⑥⑦⑧ 版本：列表 / 版本定义 / 保存草稿（乐观锁 409）
    // ==================================================================

    @GetMapping("/{id}/versions")
    public R<List<WorkflowVersionVO>> versions(@PathVariable Long id, Authentication auth) {
        access.requireWorkflowOwner(id, auth);
        return R.ok(definitionService.listVersions(id));
    }

    @GetMapping("/{id}/versions/{versionId}")
    public R<WorkflowVersionDetailVO> versionDefinition(@PathVariable Long id, @PathVariable Long versionId,
                                                        Authentication auth) {
        access.requireWorkflowOwner(id, auth);
        return R.ok(definitionService.versionDefinition(id, versionId));
    }

    @PutMapping("/{id}/versions/draft")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN','SUPER_ADMIN')")
    public R<WorkflowVersionDetailVO> saveDraft(@PathVariable Long id,
                                                @RequestBody(required = false) WorkflowDraftSaveRequest request,
                                                Authentication auth, HttpServletRequest httpRequest) {
        access.requireWorkflowOwner(id, auth);
        // 与 validate 同一契约：空 body 由这里给 400（WORKFLOW_PARAM_INVALID），
        // 否则 Jackson 先抛 HttpMessageNotReadableException → 500，下面的守卫永远轮不到。
        requireBody(request);
        WorkflowVersion draft = definitionService.saveDraft(id, request, (Long) auth.getPrincipal(), auth,
                httpRequest.getRemoteAddr());
        // 连同 definition 一起回显：客户端下一次保存必须带回新的 baseVersion 令牌，
        // 而"服务端存下的到底是什么"也应当在这一步可核对（§4.6 乐观锁契约）。
        return R.ok(definitionService.detailOf(draft));
    }

    // ==================================================================
    // ⑨⑩⑪ 校验 / 发布 / 触发运行
    // ==================================================================

    @PostMapping("/{id}/validate")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN','SUPER_ADMIN')")
    public R<WorkflowValidateResultVO> validate(@PathVariable Long id,
                                                @RequestBody(required = false) WorkflowValidateRequest request,
                                                Authentication auth) {
        access.requireWorkflowOwner(id, auth);
        // 空 body 走 400（WORKFLOW_PARAM_INVALID），而不是被 Jackson 抛成 500：
        // 历史上这里没有 required=false，前端调预校验时不发 body，异常先于所有权
        // 校验被抛出来，前端把 500 当成"无 issues"显示成"校验通过"，ERROR 工作流
        // 因此被放行到发布按钮（最终仍被 422 拦下，但预校验形同虚设）。
        requireBody(request);
        return R.ok(definitionService.validate(request.getDefinition()));
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN','SUPER_ADMIN')")
    public R<WorkflowVersionVO> publish(@PathVariable Long id,
                                        @RequestBody(required = false) WorkflowPublishRequest request,
                                        Authentication auth, HttpServletRequest httpRequest) {
        access.requireWorkflowOwner(id, auth);
        WorkflowVersion version = definitionService.publish(id,
                request, (Long) auth.getPrincipal(), auth,
                httpRequest.getRemoteAddr());
        return R.ok(WorkflowVersionVO.from(version));
    }

    @PostMapping(value = "/{id}/nodes/code/test", consumes = "application/json")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN','SUPER_ADMIN')")
    public R<Object> testCode(@PathVariable Long id, Authentication auth, HttpServletRequest request) throws IOException {
        access.requireWorkflowOwner(id, auth);
        auditLogService.logSync((Long) auth.getPrincipal(), AuditUsers.name(auth), "WORKFLOW_CODE_SAMPLE_TEST",
                "workflow", id.toString(), null, request.getRemoteAddr(), "attempted");
        return R.ok(codeSampleService.execute(request.getInputStream()));
    }

    @PostMapping("/{id}/runs")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN','SUPER_ADMIN')")
    public R<WorkflowApiPresenter.RunControlSummary> triggerRun(@PathVariable Long id,
                                     @RequestBody(required = false) WorkflowRunTriggerRequest request,
                                     Authentication auth) {
        Workflow workflow = access.requireWorkflowOwner(id, auth);
        WorkflowRunTriggerRequest body = request == null ? new WorkflowRunTriggerRequest() : request;
        Long versionId = body.getVersionId() != null ? body.getVersionId() : workflow.getCurrentVersionId();
        if (versionId == null) {
            throw new BusinessException(409, "NOT_PUBLISHED: 尚未发布任何版本，请先发布");
        }
        WorkflowRun run = runService.startRun(id, versionId, body.getInput(), (Long) auth.getPrincipal(),
                body.getIdempotencyKey(), "manual");
        return R.ok(presenter.runControlSummary(run));
    }

    // ==================================================================
    // ⑫⑬⑭ 运行：列表 / 详情 / 取消
    // ==================================================================

    @GetMapping("/runs")
    public R<Page<WorkflowApiPresenter.RunControlSummary>> runs(
            @RequestParam(required = false) Long workflowId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication auth) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0),
                Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        String statusFilter = blankToNull(status);
        Long userId = (Long) auth.getPrincipal();
        Page<WorkflowRun> runs = WorkflowAccess.isAdmin(auth)
                ? runRepository.findAllFiltered(statusFilter, workflowId, pageable)
                : runRepository.findVisibleToUser(userId, statusFilter, workflowId, pageable);
        return R.ok(runs.map(presenter::runControlSummary));
    }

    @GetMapping("/runs/{runId}")
    public R<WorkflowRunDetailVO> runDetail(@PathVariable String runId, Authentication auth) {
        WorkflowRun run = access.requireRunAccess(runId, auth);
        return R.ok(presenter.runDetail(run, nodeRunRepository.findByRunId(runId)));
    }

    @PostMapping("/runs/{runId}/cancel")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN','SUPER_ADMIN')")
    public R<WorkflowApiPresenter.RunControlSummary> cancelRun(@PathVariable String runId, Authentication auth) {
        WorkflowRun run = access.requireRunControl(runId, auth);
        runService.requestCancel(runId, (Long) auth.getPrincipal());
        return R.ok(presenter.runControlSummary(runRepository.findById(runId).orElse(run)));
    }

    // ==================================================================
    // ⑮ POST /runs/{runId}/nodes/{nodeKey}/test-run —— 单节点试运行
    // ==================================================================

    @PostMapping("/runs/{runId}/nodes/{nodeKey}/test-run")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN','SUPER_ADMIN')")
    public R<WorkflowTestRunVO> testRun(@PathVariable String runId, @PathVariable String nodeKey,
                                        Authentication auth) {
        WorkflowRun run = access.requireRunAccess(runId, auth);
        // §6.1 规定 test-run 的数据级校验是 requireWorkflowOwner（不是 requireRunAccess）：
        // 试运行会读出渲染后的脚本，只有工作流属主可以。
        Workflow workflow = access.requireWorkflowOwner(run.getWorkflowId(), auth);
        WorkflowTestRunVO result = testRunService.testRun(run, workflow, nodeKey,
                (Long) auth.getPrincipal(), AuditUsers.name(auth));
        return R.ok(result);
    }

    // ==================================================================
    // ⑯⑰ 审批：待我审批 / 决策
    // ==================================================================

    @GetMapping("/approvals/pending")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public R<List<Object>> pendingApprovals(Authentication auth) {
        access.requireApprovalAdmin(auth);
        // 只返回 pending 且未过期（过期件由 WorkflowWatchdog 置 timeout）
        List<WorkflowApproval> approvals = approvalRepository.findByStatusAndExpiresAtAfter("pending", LocalDateTime.now());
        return R.ok(approvals.stream().filter(approval -> access.canControlApproval(auth, approval))
                .map(approval -> approvalResponse(approval, access.canViewApproval(auth, approval)))
                .toList());
    }

    @PostMapping("/approvals/{approvalId}/decision")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public R<Object> decideApproval(@PathVariable Long approvalId,
                                              @Valid @RequestBody WorkflowApprovalDecisionRequest request,
                                              Authentication auth) {
        access.requireApprovalAdmin(auth);
        requireBody(request);
        WorkflowApproval existing = approvalRepository.findById(approvalId)
                .orElseThrow(() -> new BusinessException(404, "APPROVAL_NOT_FOUND"));
        access.requireApprovalAdmin(auth, existing);
        if (!"pending".equals(existing.getStatus())) {
            throw new BusinessException(409, "APPROVAL_DECIDED");
        }
        boolean viewBeforeDecision = access.canViewApproval(auth, existing);
        if (!"rejected".equals(request.getDecision())) {
            access.requireApprovalContentAccess(auth, existing);
        }
        WorkflowApproval approval = resumeService.decide(approvalId, request.getDecision(),
                (Long) auth.getPrincipal(), request.getComment());
        if (approval == null) return R.ok(null);
        return R.ok(approvalResponse(approval, viewBeforeDecision && access.canViewApproval(auth, approval)));
    }

    private Object approvalResponse(WorkflowApproval approval, boolean canView) {
        if (canView) {
            try {
                return presenter.approval(approval);
            } catch (Exception e) {
                log.warn("Unable to assemble approval detail; returning restricted summary");
            }
        }
        return presenter.restrictedApproval(approval);
    }

    // ------------------------------------------------------------------

    /** 缺请求体一律 400（而不是让 Jackson 抛成 500） */
    private static void requireBody(Object body) {
        if (body == null) {
            throw new BusinessException(400, "WORKFLOW_PARAM_INVALID: 请求体不能为空");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
