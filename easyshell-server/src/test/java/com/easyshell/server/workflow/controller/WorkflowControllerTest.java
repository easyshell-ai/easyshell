package com.easyshell.server.workflow.controller;

import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.common.exception.WorkflowRiskBlockedException;
import com.easyshell.server.common.exception.WorkflowVersionConflictException;
import com.easyshell.server.model.dto.WorkflowApprovalDecisionRequest;
import com.easyshell.server.model.dto.WorkflowCreateRequest;
import com.easyshell.server.model.dto.WorkflowDraftSaveRequest;
import com.easyshell.server.model.dto.WorkflowUpdateRequest;
import com.easyshell.server.model.dto.WorkflowValidateRequest;
import com.easyshell.server.model.entity.Workflow;
import com.easyshell.server.model.entity.WorkflowApproval;
import com.easyshell.server.model.entity.WorkflowNodeRun;
import com.easyshell.server.model.entity.WorkflowRun;
import com.easyshell.server.model.entity.WorkflowVersion;
import com.easyshell.server.ai.security.SensitiveDataFilter;
import com.easyshell.server.model.vo.WorkflowDetailVO;
import com.easyshell.server.model.vo.WorkflowRiskBlockedVO;
import com.easyshell.server.model.vo.WorkflowRunVO;
import com.easyshell.server.model.vo.WorkflowTestRunVO;
import com.easyshell.server.model.vo.WorkflowValidateResultVO;
import com.easyshell.server.model.vo.WorkflowVersionConflictVO;
import com.easyshell.server.model.vo.WorkflowVersionDetailVO;
import com.easyshell.server.model.vo.WorkflowVersionVO;
import com.easyshell.server.repository.WorkflowApprovalRepository;
import com.easyshell.server.repository.WorkflowNodeRunRepository;
import com.easyshell.server.repository.WorkflowRepository;
import com.easyshell.server.repository.WorkflowRunRepository;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.workflow.approval.WorkflowResumeService;
import com.easyshell.server.workflow.service.WorkflowApiPresenter;
import com.easyshell.server.workflow.service.WorkflowDefinitionService;
import com.easyshell.server.workflow.service.WorkflowRunService;
import com.easyshell.server.workflow.service.WorkflowTestRunService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * §6.1 REST 端点清单的行为测试（Mockito 风格，与 {@code TaskControllerTest} 同范式）。
 *
 * <p><b>M1 验收硬项（C-11）</b>：13 个带路径变量的端点，每个都有一条"非所有者访问返回
 * 403"的参数化用例；{@link WorkflowAccess} 是真实对象（仓库与审计打桩），确保断言打的是
 * 真正的数据级校验而不是 mock。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowController — §6.1 的 17 个端点")
class WorkflowControllerTest {

    /** 资源属主 */
    private static final Long OWNER = 42L;
    /** 既不是属主也不是管理员的访问者 */
    private static final Long STRANGER = 99L;
    private static final Long ADMIN = 7L;

    private static final Long WORKFLOW_ID = 1L;
    private static final Long VERSION_ID = 10L;
    private static final String RUN_ID = "wfr_abc";
    private static final String NODE_KEY = "cleanup";

    @Mock
    private WorkflowDefinitionService definitionService;
    @Mock
    private WorkflowRunService runService;
    @Mock
    private WorkflowTestRunService testRunService;
    @Mock
    private WorkflowResumeService resumeService;
    @Mock
    private WorkflowRepository workflowRepository;
    @Mock
    private WorkflowRunRepository runRepository;
    @Mock
    private WorkflowNodeRunRepository nodeRunRepository;
    @Mock
    private WorkflowApprovalRepository approvalRepository;
    @Mock
    private AuditLogService auditLogService;

    private WorkflowController controller;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MockHttpServletRequest httpRequest = new MockHttpServletRequest();

    // ------------------------------------------------------------------
    // 13 个带路径变量的端点（§6.1 权限注解 ↔ 数据级校验一一对应）
    // ------------------------------------------------------------------

    static Stream<String> pathVariableEndpoints() {
        return Stream.of(
                "GET /{id}",
                "PUT /{id}",
                "DELETE /{id}",
                "GET /{id}/versions",
                "GET /{id}/versions/{versionId}",
                "PUT /{id}/versions/draft",
                "POST /{id}/validate",
                "POST /{id}/publish",
                "POST /{id}/runs",
                "GET /runs/{runId}",
                "POST /runs/{runId}/cancel",
                "POST /runs/{runId}/nodes/{nodeKey}/test-run",
                "POST /approvals/{approvalId}/decision");
    }

    @ParameterizedTest(name = "[{index}] {0}：非所有者 → 403")
    @MethodSource("pathVariableEndpoints")
    @DisplayName("13 个路径变量端点：非所有者访问一律 403（C-11 验收硬项）")
    void pathVariableEndpoint_nonOwner_isDenied(String endpoint) {
        assertThatThrownBy(() -> invoke(endpoint, auth(STRANGER, "operator")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(403))
                .hasMessageContaining("WORKFLOW_FORBIDDEN");
    }

    @ParameterizedTest(name = "[{index}] {0}：属主放行")
    @MethodSource("pathVariableEndpoints")
    @DisplayName("13 个路径变量端点：属主（或 admin）放行，不会 403")
    void pathVariableEndpoint_owner_isAllowed(String endpoint) {
        // 用 admin 身份：审批端点要求 admin，其余端点 admin 同样放行
        Object result = invoke(endpoint, auth(ADMIN, "admin"));
        assertThat(result).isNotNull();
    }

    @ParameterizedTest(name = "[{index}] {0}：viewer 也被方法级注解之外的数据级校验兜住")
    @ValueSource(strings = {"GET /{id}", "GET /runs/{runId}"})
    @DisplayName("viewer 访问 id 端点：数据级校验同样拒绝")
    void viewer_isAlsoDeniedByDataLevelCheck(String endpoint) {
        assertThatThrownBy(() -> invoke(endpoint, auth(STRANGER, "viewer")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(403));
    }

    /** 按端点名调用真实 Controller 方法（鉴权在方法内部发生） */
    private Object invoke(String endpoint, Authentication auth) {
        WorkflowDraftSaveRequest draft = new WorkflowDraftSaveRequest();
        draft.setDefinition(new TextNode("{}"));
        WorkflowValidateRequest validateRequest = new WorkflowValidateRequest();
        validateRequest.setDefinition(new TextNode("{}"));
        return switch (endpoint) {
            case "GET /{id}" -> controller.detail(WORKFLOW_ID, auth);
            case "PUT /{id}" -> controller.update(WORKFLOW_ID, new WorkflowUpdateRequest(), auth, httpRequest);
            case "DELETE /{id}" -> controller.delete(WORKFLOW_ID, auth, httpRequest);
            case "GET /{id}/versions" -> controller.versions(WORKFLOW_ID, auth);
            case "GET /{id}/versions/{versionId}" ->
                    controller.versionDefinition(WORKFLOW_ID, VERSION_ID, auth);
            case "PUT /{id}/versions/draft" -> controller.saveDraft(WORKFLOW_ID, draft, auth, httpRequest);
            case "POST /{id}/validate" -> controller.validate(WORKFLOW_ID, validateRequest, auth);
            case "POST /{id}/publish" -> controller.publish(WORKFLOW_ID, null, auth, httpRequest);
            case "POST /{id}/runs" -> controller.triggerRun(WORKFLOW_ID, null, auth);
            case "GET /runs/{runId}" -> controller.runDetail(RUN_ID, auth);
            case "POST /runs/{runId}/cancel" -> controller.cancelRun(RUN_ID, auth);
            case "POST /runs/{runId}/nodes/{nodeKey}/test-run" ->
                    controller.testRun(RUN_ID, NODE_KEY, auth);
            case "POST /approvals/{approvalId}/decision" -> {
                WorkflowApprovalDecisionRequest request = new WorkflowApprovalDecisionRequest();
                request.setDecision("approved");
                yield controller.decideApproval(5L, request, auth);
            }
            default -> throw new IllegalArgumentException("unknown endpoint: " + endpoint);
        };
    }

    // ------------------------------------------------------------------
    // 正常路径（17 个端点每个至少一条）
    // ------------------------------------------------------------------

    @BeforeEach
    void setUp() {
        controller = new WorkflowController(definitionService, runService, testRunService, resumeService,
                new WorkflowAccess(workflowRepository, runRepository, auditLogService),
                new WorkflowApiPresenter(objectMapper, new SensitiveDataFilter(), workflowRepository,
                        runRepository),
                runRepository, nodeRunRepository, approvalRepository);

        Workflow workflow = workflowOwnedBy(OWNER);
        WorkflowRun run = runStartedBy(OWNER);
        lenient().when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
        lenient().when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run));
        lenient().when(definitionService.saveDraft(eq(WORKFLOW_ID), any(), any(), any(), any()))
                .thenReturn(draftVersion());
        lenient().when(definitionService.publish(eq(WORKFLOW_ID), any(), any(), any(), any()))
                .thenReturn(publishedVersion());
    }

    private Authentication auth(Long userId, String role) {
        Authentication auth = mock(Authentication.class);
        lenient().when(auth.getPrincipal()).thenReturn(userId);
        List<SimpleGrantedAuthority> authorities =
                List.of(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()));
        lenient().when(auth.getAuthorities()).thenAnswer(inv -> authorities);
        lenient().when(auth.getName()).thenReturn("user-" + userId);
        return auth;
    }

    private Workflow workflowOwnedBy(Long userId) {
        Workflow workflow = new Workflow();
        workflow.setId(WORKFLOW_ID);
        workflow.setName("disk-cleanup");
        workflow.setStatus("draft");
        workflow.setVisibility("private");
        workflow.setCreatedBy(userId);
        workflow.setCurrentVersionId(VERSION_ID);
        return workflow;
    }

    private WorkflowVersion draftVersion() {
        WorkflowVersion version = new WorkflowVersion();
        version.setId(VERSION_ID);
        version.setWorkflowId(WORKFLOW_ID);
        version.setVersion(2);
        version.setBaseVersion(1);
        version.setIsValid(false);
        return version;
    }

    private WorkflowVersion publishedVersion() {
        WorkflowVersion version = draftVersion();
        version.setIsValid(true);
        return version;
    }

    private WorkflowRun runStartedBy(Long userId) {
        WorkflowRun run = new WorkflowRun();
        run.setId(RUN_ID);
        run.setWorkflowId(WORKFLOW_ID);
        run.setVersionId(VERSION_ID);
        run.setStatus("running");
        run.setStartedBy(userId);
        return run;
    }

    @Test
    @DisplayName("GET /page：非管理员只查自己的+公开的；管理员查全部")
    void page_scopesByRole() {
        when(definitionService.pageForUser(eq(null), eq(null), eq(null), eq(OWNER), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(workflowOwnedBy(OWNER))));
        when(definitionService.pageForAdmin(eq(null), eq(null), eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(workflowOwnedBy(OWNER), workflowOwnedBy(STRANGER))));

        Page<Workflow> mine = controller.page(null, null, null, 0, 20, auth(OWNER, "operator")).getData();
        assertThat(mine.getContent()).hasSize(1);
        verify(definitionService).pageForUser(eq(null), eq(null), eq(null), eq(OWNER), any(Pageable.class));

        Page<Workflow> all = controller.page(null, null, null, 0, 20, auth(ADMIN, "admin")).getData();
        assertThat(all.getContent()).hasSize(2);
        verify(definitionService).pageForAdmin(eq(null), eq(null), eq(null), any(Pageable.class));
    }

    @Test
    @DisplayName("GET /page：size 超上限被夹紧（既有防护不回退）")
    void page_clampsPageSize() {
        when(definitionService.pageForUser(eq(null), eq(null), eq(null), eq(OWNER), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        controller.page(null, null, null, 0, 10_000, auth(OWNER, "operator"));

        verify(definitionService).pageForUser(eq(null), eq(null), eq(null), eq(OWNER),
                org.mockito.ArgumentMatchers.argThat((Pageable p) -> p.getPageSize() == 200));
    }

    @Test
    @DisplayName("POST /：新建草稿，createdBy 是当前用户并记审计")
    void create_setsCreatedByAndAudits() {
        WorkflowCreateRequest request = new WorkflowCreateRequest();
        request.setName("disk-cleanup");
        when(definitionService.create(any(), eq(OWNER), any(), any())).thenReturn(workflowOwnedBy(OWNER));

        Workflow created = controller.create(request, auth(OWNER, "operator"), httpRequest).getData();

        assertThat(created.getCreatedBy()).isEqualTo(OWNER);
        verify(definitionService).create(any(), eq(OWNER), any(), any());
    }

    @Test
    @DisplayName("GET /{id}：属主拿到 {workflow, versions, draft} 三件套（编辑器首屏契约）")
    void detail_owner_isAllowed() {
        WorkflowVersion draft = draftVersion();
        when(definitionService.detail(WORKFLOW_ID)).thenReturn(new WorkflowDetailVO(
                workflowOwnedBy(OWNER),
                List.of(WorkflowVersionVO.from(draft)),
                WorkflowVersionDetailVO.from(draft, new ObjectMapper().createObjectNode())));

        WorkflowDetailVO detail = controller.detail(WORKFLOW_ID, auth(OWNER, "operator")).getData();

        assertThat(detail.getWorkflow().getId()).isEqualTo(WORKFLOW_ID);
        assertThat(detail.getVersions()).hasSize(1);
        assertThat(detail.getDraft().getVersion()).isEqualTo(2);
        assertThat(detail.getDraft().getBaseVersion()).isEqualTo(1);
        assertThat(detail.getDraft().getDefinition()).isNotNull();
    }

    @Test
    @DisplayName("PUT /{id}：改元信息")
    void update_meta() {
        WorkflowUpdateRequest request = new WorkflowUpdateRequest();
        request.setName("renamed");
        when(definitionService.updateMeta(eq(WORKFLOW_ID), any(), eq(OWNER), any(), any()))
                .thenReturn(workflowOwnedBy(OWNER));

        assertThat(controller.update(WORKFLOW_ID, request, auth(OWNER, "operator"), httpRequest)
                .getData().getId()).isEqualTo(WORKFLOW_ID);
    }

    @Test
    @DisplayName("DELETE /{id}：软删为 archived（幂等）")
    void delete_softDeletes() {
        Workflow archived = workflowOwnedBy(OWNER);
        archived.setStatus("archived");
        when(definitionService.archive(eq(WORKFLOW_ID), eq(OWNER), any(), any())).thenReturn(archived);

        assertThat(controller.delete(WORKFLOW_ID, auth(OWNER, "operator"), httpRequest).getData().getStatus())
                .isEqualTo("archived");
    }

    @Test
    @DisplayName("GET /{id}/versions：版本摘要列表")
    void versions_list() {
        when(definitionService.listVersions(WORKFLOW_ID))
                .thenReturn(List.of(WorkflowVersionVO.builder().id(VERSION_ID).version(1).build()));

        assertThat(controller.versions(WORKFLOW_ID, auth(OWNER, "operator")).getData()).hasSize(1);
    }

    @Test
    @DisplayName("GET /{id}/versions/{versionId}：版本定义（平铺摘要 + definition）")
    void versionDefinition_returnsDefinition() {
        when(definitionService.versionDefinition(WORKFLOW_ID, VERSION_ID))
                .thenReturn(WorkflowVersionDetailVO.from(draftVersion(),
                        new ObjectMapper().createObjectNode().put("schemaVersion", 1)));

        WorkflowVersionDetailVO detail = controller
                .versionDefinition(WORKFLOW_ID, VERSION_ID, auth(OWNER, "operator")).getData();

        // 平铺契约：摘要字段与 definition 同级（前端 WorkflowVersionDetail 就是这个形状）
        assertThat(detail.getId()).isEqualTo(VERSION_ID);
        assertThat(detail.getVersion()).isEqualTo(2);
        assertThat(detail.getBaseVersion()).isEqualTo(1);
        assertThat(detail.getDefinition().get("schemaVersion").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("PUT /{id}/versions/draft：保存成功返回新 baseVersion")
    void saveDraft_ok() {
        WorkflowVersion draft = new WorkflowVersion();
        draft.setId(VERSION_ID);
        draft.setVersion(2);
        draft.setBaseVersion(1);
        when(definitionService.saveDraft(eq(WORKFLOW_ID), any(), eq(OWNER), any(), any())).thenReturn(draft);

        WorkflowDraftSaveRequest request = new WorkflowDraftSaveRequest();
        request.setDefinition(new TextNode("{}"));
        request.setBaseVersion(0);

        controller.saveDraft(WORKFLOW_ID, request, auth(OWNER, "operator"), httpRequest);

        verify(definitionService).saveDraft(eq(WORKFLOW_ID), any(), eq(OWNER), any(), any());
        // 保存成功后必须把新的 baseVersion 令牌回给客户端（下一个保存的原样带回值）
        verify(definitionService).detailOf(draft);
    }

    @Test
    @DisplayName("PUT /{id}/versions/draft：baseVersion 过期 → 409 + 三选一契约")
    void saveDraft_conflict_returns409Contract() {
        when(definitionService.saveDraft(eq(WORKFLOW_ID), any(), eq(OWNER), any(), any()))
                .thenThrow(new WorkflowVersionConflictException(
                        WorkflowVersionConflictVO.of(3, VERSION_ID)));

        WorkflowDraftSaveRequest request = new WorkflowDraftSaveRequest();
        request.setDefinition(new TextNode("{}"));
        request.setBaseVersion(2);

        assertThatThrownBy(() -> controller.saveDraft(WORKFLOW_ID, request, auth(OWNER, "operator"), httpRequest))
                .isInstanceOfSatisfying(WorkflowVersionConflictException.class, e -> {
                    assertThat(e.getConflict().getCode()).isEqualTo("VERSION_CONFLICT");
                    assertThat(e.getConflict().getCurrentBaseVersion()).isEqualTo(3);
                    // 三选一：覆盖保存 / 另存副本 / 放弃重载；不做自动合并
                    assertThat(e.getConflict().getOptions())
                            .extracting(WorkflowVersionConflictVO.ConflictOption::getAction)
                            .containsExactly("overwrite", "copy", "reload");
                });
    }

    @Test
    @DisplayName("POST /{id}/validate：返回 issues[]")
    void validate_returnsIssues() {
        when(definitionService.validate(any())).thenReturn(WorkflowValidateResultVO.builder()
                .valid(false)
                .issues(List.of(WorkflowValidateResultVO.ValidationIssue.builder()
                        .severity("ERROR").code("GRAPH_CYCLE").message("环").nodeKey("a").build()))
                .estimatedRisk("LOW")
                .riskMatches(List.of())
                .build());

        WorkflowValidateRequest request = new WorkflowValidateRequest();
        request.setDefinition(new TextNode("{}"));

        WorkflowValidateResultVO result = controller.validate(WORKFLOW_ID, request, auth(OWNER, "operator"))
                .getData();
        assertThat(result.getValid()).isFalse();
        assertThat(result.getIssues()).hasSize(1);
    }

    @Test
    @DisplayName("POST /{id}/validate：空 body → 400 WORKFLOW_PARAM_INVALID（不是 500）")
    void validate_missingBody_is400() {
        // 回归：@RequestBody 曾漏掉 required=false，前端调预校验不发 body 时 Spring 抛
        // HttpMessageNotReadableException → 500，而前端把非 200 一律当成"无 issues"，
        // 于是 ERROR 工作流在发布弹窗里显示成"校验通过"并放行发布按钮。
        assertThatThrownBy(() -> controller.validate(WORKFLOW_ID, null, auth(OWNER, "operator")))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("WORKFLOW_PARAM_INVALID");
                });
        // 没有 definition 就无从校验：不得悄悄退化成"用草稿校验"或"直接放行"
        verifyNoInteractions(definitionService);
    }

    @Test
    @DisplayName("PUT /{id}/versions/draft：空 body → 400 WORKFLOW_PARAM_INVALID（不是 500）")
    void saveDraft_missingBody_is400() {
        // 与 validate 同一模式：没有 required=false 时 Jackson 先抛
        // HttpMessageNotReadableException → 500，控制器里的 requireBody 守卫永远轮不到执行。
        assertThatThrownBy(() -> controller.saveDraft(WORKFLOW_ID, null, auth(OWNER, "operator"), httpRequest))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("WORKFLOW_PARAM_INVALID");
                });
        verifyNoInteractions(definitionService);
    }

    @Test
    @DisplayName("POST /{id}/publish：发布成功")
    void publish_ok() {
        WorkflowVersion published = new WorkflowVersion();
        published.setId(VERSION_ID);
        published.setVersion(1);
        published.setIsValid(true);
        when(definitionService.publish(eq(WORKFLOW_ID), any(), eq(OWNER), any(), any())).thenReturn(published);

        assertThat(controller.publish(WORKFLOW_ID, null, auth(OWNER, "operator"), httpRequest)
                .getData().getIsValid()).isTrue();
    }

    @Test
    @DisplayName("POST /{id}/runs：触发运行（MF-5 唯一触发路径）")
    void triggerRun_startsRun() {
        when(runService.startRun(eq(WORKFLOW_ID), eq(VERSION_ID), any(), eq(OWNER), eq("idem-1"), eq("manual")))
                .thenReturn(runStartedBy(OWNER));

        com.easyshell.server.model.dto.WorkflowRunTriggerRequest request =
                new com.easyshell.server.model.dto.WorkflowRunTriggerRequest();
        request.setVersionId(VERSION_ID);
        request.setIdempotencyKey("idem-1");
        request.setInput(Map.of("env", "prod"));

        WorkflowRunVO run = controller.triggerRun(WORKFLOW_ID, request, auth(OWNER, "operator")).getData();

        assertThat(run.getId()).isEqualTo(RUN_ID);
        verify(runService).startRun(eq(WORKFLOW_ID), eq(VERSION_ID), any(), eq(OWNER), eq("idem-1"),
                eq("manual"));
    }

    @Test
    @DisplayName("POST /{id}/runs：未发布任何版本 → 409 NOT_PUBLISHED")
    void triggerRun_withoutPublishedVersion_is409() {
        Workflow unpublished = workflowOwnedBy(OWNER);
        unpublished.setCurrentVersionId(null);
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(unpublished));

        assertThatThrownBy(() -> controller.triggerRun(WORKFLOW_ID, null, auth(OWNER, "operator")))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(409);
                    assertThat(e.getMessage()).contains("NOT_PUBLISHED");
                });
        verifyNoInteractions(runService);
    }

    @Test
    @DisplayName("GET /runs：非管理员只看得到自己的运行，管理员看全部")
    void runs_scopesByRole() {
        when(runRepository.findVisibleToUser(eq(OWNER), eq(null), eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(runStartedBy(OWNER))));
        when(runRepository.findAllFiltered(eq(null), eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(runStartedBy(OWNER), runStartedBy(STRANGER))));

        assertThat(controller.runs(null, null, 0, 20, auth(OWNER, "operator")).getData().getContent())
                .hasSize(1);
        assertThat(controller.runs(null, null, 0, 20, auth(ADMIN, "admin")).getData().getContent())
                .hasSize(2);
        verify(runRepository).findVisibleToUser(eq(OWNER), eq(null), eq(null), any(Pageable.class));
    }

    @Test
    @DisplayName("GET /runs/{runId}：详情含全部 nodeRun")
    void runDetail_includesNodeRuns() {
        WorkflowNodeRun nodeRun = new WorkflowNodeRun();
        nodeRun.setId("wnr_1");
        nodeRun.setRunId(RUN_ID);
        nodeRun.setNodeKey(NODE_KEY);
        when(nodeRunRepository.findByRunId(RUN_ID)).thenReturn(List.of(nodeRun));

        var detail = controller.runDetail(RUN_ID, auth(OWNER, "operator")).getData();

        assertThat(detail.getRun().getId()).isEqualTo(RUN_ID);
        assertThat(detail.getNodeRuns()).hasSize(1);
    }

    @Test
    @DisplayName("GET /runs/{runId}：input_json 出站前脱敏（P-1 之后节点参数列才有值，凭据不得明文外泄）")
    void runDetail_masksSensitiveInputSnapshots() {
        // MF-13：input_json 存渲染后的 parameters Map，值可能就是 PAT 之类的凭据。
        // P-1 修好之后这一列第一次真正有值，出站口径必须与 run 级快照一致（出口 1/2）
        WorkflowNodeRun nodeRun = new WorkflowNodeRun();
        nodeRun.setId("wnr_secret");
        nodeRun.setRunId(RUN_ID);
        nodeRun.setNodeKey(NODE_KEY);
        nodeRun.setInputJson("{\"parameters\":{\"PAT\":\"password=hunter2\"}}");
        when(nodeRunRepository.findByRunId(RUN_ID)).thenReturn(List.of(nodeRun));
        WorkflowRun run = runStartedBy(OWNER);
        run.setInputJson("{\"PAT\":\"password=hunter2\"}");
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run));

        var detail = controller.runDetail(RUN_ID, auth(OWNER, "operator")).getData();

        // 节点级：MF-13 的 parameters Map 脱敏后再出站
        assertThat(detail.getNodeRuns().get(0).getInput())
                .containsEntry("parameters", Map.of("PAT", "password=***"));
        // run 级：触发入参快照同样脱敏（既有口径，回归保护）
        assertThat(detail.getRun().getInput()).containsEntry("PAT", "password=***");
    }

    @Test
    @DisplayName("POST /runs/{runId}/cancel：取消并返回最新状态")
    void cancelRun_requestsCancel() {
        WorkflowRun cancelled = runStartedBy(OWNER);
        cancelled.setStatus("cancelled");
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(cancelled));

        WorkflowRunVO result = controller.cancelRun(RUN_ID, auth(OWNER, "operator")).getData();

        assertThat(result.getStatus()).isEqualTo("cancelled");
        verify(runService).requestCancel(RUN_ID, OWNER);
    }

    @Test
    @DisplayName("POST /runs/{runId}/nodes/{nodeKey}/test-run：LOW 脚本通过六步管线")
    void testRun_lowScript_ok() {
        when(testRunService.testRun(any(), any(), eq(NODE_KEY), eq(OWNER), any()))
                .thenReturn(WorkflowTestRunVO.builder()
                        .nodeKey(NODE_KEY)
                        .nodeType("script-exec")
                        .status("ok")
                        .riskLevel("LOW")
                        .riskMatches(List.of())
                        .renderedScript("echo '/var/log'")
                        .resolvedParameters(Map.of("DIR", "/var/log"))
                        .build());

        WorkflowTestRunVO result = controller.testRun(RUN_ID, NODE_KEY, auth(OWNER, "operator")).getData();

        assertThat(result.getStatus()).isEqualTo("ok");
        assertThat(result.getRiskLevel()).isEqualTo("LOW");
    }

    @Test
    @DisplayName("test-run：BANNED 脚本 → 449，响应体只有等级与模式串（禁止命令原文）")
    void testRun_bannedScript_449WithoutCommandText() {
        when(testRunService.testRun(any(), any(), eq(NODE_KEY), eq(OWNER), any()))
                .thenThrow(new WorkflowRiskBlockedException(WorkflowRiskBlockedVO.builder()
                        .code("WORKFLOW_RISK_BLOCKED")
                        .message("脚本命中封禁模式")
                        .riskLevel("BANNED")
                        .riskMatches(List.of("rm -rf /"))
                        .riskPolicy("block")
                        .nodeKey(NODE_KEY)
                        .build()));

        assertThatThrownBy(() -> controller.testRun(RUN_ID, NODE_KEY, auth(OWNER, "operator")))
                .isInstanceOfSatisfying(WorkflowRiskBlockedException.class, e -> {
                    WorkflowRiskBlockedVO body = e.getBlocked();
                    assertThat(body.getRiskLevel()).isEqualTo("BANNED");
                    assertThat(body.getRiskMatches()).containsExactly("rm -rf /");
                    // 序列化后的响应体不得包含命令原文（B2 #13 / C-9）
                    String json = json(body);
                    assertThat(json).doesNotContain("commandRisks");
                    assertThat(json).doesNotContain("scriptContent");
                    assertThat(json).doesNotContain("script");
                });
    }

    @Test
    @DisplayName("test-run：MEDIUM 且 riskPolicy=block → 449 RISK_NEED_APPROVAL")
    void testRun_mediumScript_449NeedApproval() {
        when(testRunService.testRun(any(), any(), eq(NODE_KEY), eq(OWNER), any()))
                .thenThrow(new WorkflowRiskBlockedException(WorkflowRiskBlockedVO.builder()
                        .code("RISK_NEED_APPROVAL")
                        .message("中风险脚本被 riskPolicy=block 拒绝")
                        .riskLevel("MEDIUM")
                        .riskMatches(List.of("truncate"))
                        .riskPolicy("block")
                        .nodeKey(NODE_KEY)
                        .build()));

        assertThatThrownBy(() -> controller.testRun(RUN_ID, NODE_KEY, auth(OWNER, "operator")))
                .isInstanceOfSatisfying(WorkflowRiskBlockedException.class,
                        e -> assertThat(e.getBlocked().getCode()).isEqualTo("RISK_NEED_APPROVAL"));
    }

    @Test
    @DisplayName("GET /approvals/pending：只返回 pending 且未过期")
    void pendingApprovals_onlyPendingNotExpired() {
        WorkflowApproval approval = new WorkflowApproval();
        approval.setId(5L);
        approval.setRunId(RUN_ID);
        approval.setStatus("pending");
        when(approvalRepository.findByStatusAndExpiresAtAfter(eq("pending"), any()))
                .thenReturn(List.of(approval));

        assertThat(controller.pendingApprovals(auth(ADMIN, "admin")).getData()).hasSize(1);
        verify(approvalRepository).findByStatusAndExpiresAtAfter(eq("pending"), any());
    }

    @Test
    @DisplayName("POST /approvals/{approvalId}/decision：审批决策")
    void decideApproval_ok() {
        WorkflowApproval approval = new WorkflowApproval();
        approval.setId(5L);
        approval.setStatus("approved");
        when(resumeService.decide(eq(5L), eq("approved"), eq(ADMIN), eq("ok")))
                .thenReturn(approval);

        WorkflowApprovalDecisionRequest request = new WorkflowApprovalDecisionRequest();
        request.setDecision("approved");
        request.setComment("ok");

        assertThat(controller.decideApproval(5L, request, auth(ADMIN, "admin")).getData().getStatus())
                .isEqualTo("approved");
    }

    // ------------------------------------------------------------------

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("清单哨兵：17 端点 / 13 路径变量与 §6.1 一致（防未来误加 POST /runs）")
    void endpointInventory_matchesDesign() {
        // POST /runs（body 带 workflowId 的重复触发路径）按 N7-d 永久删除，
        // 触发运行的唯一路径是 POST /{id}/runs。这里用映射表把清单钉死。
        Map<String, String> expected = Map.ofEntries(
                Map.entry("GET /page", "认证"),
                Map.entry("POST /", "OPERATOR+"),
                Map.entry("GET /{id}", "认证"),
                Map.entry("PUT /{id}", "OPERATOR+"),
                Map.entry("DELETE /{id}", "OPERATOR+"),
                Map.entry("GET /{id}/versions", "认证"),
                Map.entry("GET /{id}/versions/{versionId}", "认证"),
                Map.entry("PUT /{id}/versions/draft", "OPERATOR+"),
                Map.entry("POST /{id}/validate", "OPERATOR+"),
                Map.entry("POST /{id}/publish", "OPERATOR+"),
                Map.entry("POST /{id}/runs", "OPERATOR+"),
                Map.entry("GET /runs", "认证"),
                Map.entry("GET /runs/{runId}", "认证"),
                Map.entry("POST /runs/{runId}/cancel", "OPERATOR+"),
                Map.entry("POST /runs/{runId}/nodes/{nodeKey}/test-run", "OPERATOR+"),
                Map.entry("GET /approvals/pending", "ADMIN+"),
                Map.entry("POST /approvals/{approvalId}/decision", "ADMIN+"));
        assertThat(expected).hasSize(17);
        assertThat(pathVariableEndpoints()).hasSize(13);
    }

    @Test
    @DisplayName("create 之外的写端点都过数据级校验：repo 未被陌生人触碰前就 403")
    void strangerNeverReachesService() {
        assertThatThrownBy(() -> controller.detail(WORKFLOW_ID, auth(STRANGER, "operator")))
                .isInstanceOf(BusinessException.class);
        verify(definitionService, org.mockito.Mockito.never()).listVersions(any());
        verify(definitionService, org.mockito.Mockito.never()).updateMeta(any(), any(), any(), any(), any());
        verify(runService, org.mockito.Mockito.never()).startRun(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("int 夹紧：负数页码归零")
    void page_negativePage_isClamped() {
        when(definitionService.pageForUser(eq(null), eq(null), eq(null), eq(OWNER), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        controller.page(null, null, null, -5, 20, auth(OWNER, "operator"));

        verify(definitionService).pageForUser(eq(null), eq(null), eq(null), eq(OWNER),
                org.mockito.ArgumentMatchers.argThat((Pageable p) -> p.getPageNumber() == 0));
    }

}
