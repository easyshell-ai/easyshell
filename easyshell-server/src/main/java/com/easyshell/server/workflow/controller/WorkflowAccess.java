package com.easyshell.server.workflow.controller;

import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.entity.Workflow;
import com.easyshell.server.model.entity.WorkflowApproval;
import com.easyshell.server.model.entity.WorkflowRun;
import com.easyshell.server.repository.WorkflowRepository;
import com.easyshell.server.repository.WorkflowRunRepository;
import com.easyshell.server.security.AuditUsers;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.workflow.service.WorkflowRunProvenanceService;
import com.easyshell.server.workflow.service.WorkflowDispatchApprovalAccessService;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * 工作流 REST 端点的<b>数据级校验</b>（设计 §6.1 的唯一落点）。
 *
 * <p><b>硬规则</b>：端点的权限注解与数据级校验必须一一对应，缺一即视为漏洞。
 * 反面教材是 {@code TaskController.java:203-211}——{@code requireAccess} 全仓只有一个
 * 调用点，{@code agentJobs} 端点自己又内联实现了一遍所有权过滤。本类把三个校验函数
 * 收敛到一处，17 个端点里 13 个带路径变量的端点全部经过这里，没有第二份实现。</p>
 *
 * <p>与设计草案的差异（草案写的是 {@code public static}）：三个函数改成 Spring bean 的
 * 实例方法，因为"403 同时记审计 WARN"需要 {@link AuditLogService}，静态函数拿不到 bean
 * 会把审计改成"调用方各自记得写"，那正是本类要消灭的反模式。</p>
 *
 * <p>第二个差异：草案的 {@code requireRunAccess} 读 {@code run.getWorkflow().getCreatedBy()}，
 * 而 {@code WorkflowRun} 对 {@code Workflow} 是<b>零外键软关联</b>（§5.3），没有导航属性，
 * 因此这里按 {@code workflowId} 查一次仓库。</p>
 *
 * <p>放置位置按设计 §6.1 草案：{@code workflow/controller/WorkflowAccess.java}。</p>
 */
@Slf4j
@Component
public class WorkflowAccess {

    /** 与 TaskController.isAdmin / TaskLogWebSocketHandler 同一套 RBAC 语义 */
    private static final String ROLE_ADMIN = "ROLE_ADMIN";
    private static final String ROLE_SUPER_ADMIN = "ROLE_SUPER_ADMIN";

    static final String ACTION_DENIED = "WORKFLOW_ACCESS_DENIED";

    private final WorkflowRepository workflowRepository;
    private final WorkflowRunRepository runRepository;
    private final AuditLogService auditLogService;
    @Autowired
    private WorkflowRunProvenanceService provenanceService;
    private WorkflowDispatchApprovalAccessService dispatchApprovalAccess;

    @Autowired(required = false)
    public void setDispatchApprovalAccess(WorkflowDispatchApprovalAccessService dispatchApprovalAccess) {
        this.dispatchApprovalAccess = dispatchApprovalAccess;
    }

    public WorkflowAccess(WorkflowRepository workflowRepository,
                          WorkflowRunRepository runRepository,
                          AuditLogService auditLogService) {
        this.workflowRepository = workflowRepository;
        this.runRepository = runRepository;
        this.auditLogService = auditLogService;
    }

    // ------------------------------------------------------------------
    // 三个数据级校验函数（§6.1 权限注解列的对应物）
    // ------------------------------------------------------------------

    /**
     * 工作流属主校验：admin/super_admin 或 createdBy == 当前用户。
     *
     * <p>覆盖 GET/PUT/DELETE /{id}、GET /{id}/versions、
     * GET /{id}/versions/{versionId}、PUT /{id}/versions/draft、
     * POST /{id}/validate、POST /{id}/publish、POST /{id}/runs、
     * POST /runs/{runId}/nodes/{nodeKey}/test-run。</p>
     */
    public Workflow requireWorkflowOwner(Long workflowId, Authentication auth) {
        Long userId = principalId(auth);
        Workflow workflow = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new BusinessException(404, "WORKFLOW_NOT_FOUND: 工作流不存在"));
        if (!isAdmin(auth) && !userId.equals(workflow.getCreatedBy())) {
            deny(auth, userId, "workflow", String.valueOf(workflowId), "无权访问此工作流");
        }
        return workflow;
    }

    /**
     * 运行访问校验：admin/super_admin，或 startedBy == 当前用户，
     * 或当前用户是该 run 所属工作流的创建者（§6.1 GET /runs/{runId}、/cancel）。
     */
    public WorkflowRun requireRunAccess(String runId, Authentication auth) {
        WorkflowRun run = requireRunControl(runId, auth);
        Long userId = principalId(auth);
        boolean canView;
        try {
            canView = provenanceService.canView(userId, runId);
        } catch (Exception e) {
            canView = false;
        }
        if (!canView) {
            deny(auth, userId, "workflow_run", runId, "无权访问此运行");
        }
        return run;
    }

    /** 控制权不授予运行数据查看权，例如取消运行不应依赖主机 VIEW。 */
    public WorkflowRun requireRunControl(String runId, Authentication auth) {
        Long userId = principalId(auth);
        WorkflowRun run = runRepository.findById(runId)
                .orElseThrow(() -> new BusinessException(404, "RUN_NOT_FOUND: 运行不存在"));
        if (!isAdmin(auth) && !userId.equals(run.getStartedBy())) {
            Workflow workflow = workflowRepository.findById(run.getWorkflowId()).orElse(null);
            if (workflow == null || !userId.equals(workflow.getCreatedBy())) {
                deny(auth, userId, "workflow_run", runId, "无权访问此运行");
            }
        }
        return run;
    }

    /**
     * 审批管理员校验（§6.1 /approvals/pending、/approvals/{approvalId}/decision）。
     *
     * <p>方法级 {@code @PreAuthorize} 是第一道，这里是第二道（fail-closed）：
     * 注解被误删/误改时仍然拒。与草案的差异：草案说"做审计与二次确认"，
     * 实现为<b>拒绝时</b>记 WARN 审计 + 抛 403（放行时不写审计，否则每次打开待审批
     * 列表都刷一条审计）。</p>
     */
    public void requireApprovalAdmin(Authentication auth) {
        if (!isAdmin(auth)) {
            Long userId = principalId(auth);
            deny(auth, userId, "workflow_approval", null, "审批需要管理员权限");
        }
    }

    /** 决策时还须满足审批记录指定的角色；未知角色拒绝，避免配置损坏时降权。 */
    public void requireApprovalAdmin(Authentication auth, WorkflowApproval approval) {
        requireApprovalAdmin(auth);
        if (!canControlApproval(auth, approval)) {
            deny(auth, principalId(auth), "workflow_approval", String.valueOf(approval.getId()),
                    "审批需要指定的管理员权限");
        }
    }

    /** 列表中不暴露不属于当前管理员角色的审批，未知角色 fail-closed。 */
    public boolean canControlApproval(Authentication auth, WorkflowApproval approval) {
        if (!isAdmin(auth) || approval == null) return false;
        String role = approval.getApproverRole();
        return "ADMIN".equals(role) || ("SUPER_ADMIN".equals(role) && auth.getAuthorities().stream()
                .anyMatch(a -> ROLE_SUPER_ADMIN.equals(a.getAuthority())));
    }

    /** 控制权不意味着内容可读；缺失旧 provenance 或查询异常都只返回受限摘要。 */
    public boolean canViewApproval(Authentication auth, WorkflowApproval approval) {
        Long userId = principalId(auth);
        if (approval == null) return false;
        if ("script-exec".equals(approval.getNodeType())) {
            try {
                return dispatchApprovalAccess != null && dispatchApprovalAccess.canView(userId, approval);
            } catch (Exception invalid) {
                return false;
            }
        }
        try {
            if (provenanceService != null && provenanceService.canView(userId, approval.getRunId())) return true;
        } catch (Exception e) {
            // The approval-specific proof must independently validate every source.
        }
        try {
            return dispatchApprovalAccess != null && dispatchApprovalAccess.canView(userId, approval);
        } catch (Exception e) {
            return false;
        }
    }

    /** Only approval content access; does not authorize run detail or WebSocket subscriptions. */
    public void requireApprovalContentAccess(Authentication auth, WorkflowApproval approval) {
        requireApprovalAdmin(auth, approval);
        if (!canViewApproval(auth, approval)) {
            deny(auth, principalId(auth), "workflow_approval", String.valueOf(approval.getId()), "无权访问此审批内容");
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    public static boolean isAdmin(Authentication auth) {
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> ROLE_ADMIN.equals(a.getAuthority()) || ROLE_SUPER_ADMIN.equals(a.getAuthority()));
    }

    private static Long principalId(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof Long userId)) {
            throw new BusinessException(403, "WORKFLOW_FORBIDDEN: 未认证的访问");
        }
        return userId;
    }

    /** 403 + WARN 审计（§6.1：越权必须留痕，result=denied） */
    private void deny(Authentication auth, Long userId, String resourceType, String resourceId, String reason) {
        log.warn("Workflow access denied: user={} resource={}/{} reason={}",
                userId, resourceType, resourceId, reason);
        try {
            auditLogService.logSync(userId, AuditUsers.name(auth), ACTION_DENIED, resourceType, resourceId,
                    reason, null, "denied");
        } catch (Exception e) {
            // 审计写失败不能把 403 变成 500：越权判定本身必须照常返回
            log.error("越权审计写失败（仍返回 403）: user={} resource={}/{} err={}",
                    userId, resourceType, resourceId, e.getMessage());
        }
        throw new BusinessException(403, "WORKFLOW_FORBIDDEN: " + reason);
    }
}
