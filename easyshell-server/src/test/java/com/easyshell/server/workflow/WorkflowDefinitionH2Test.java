package com.easyshell.server.workflow;

import com.easyshell.server.ai.config.AgenticConfigService;
import com.easyshell.server.ai.risk.CommandRiskEngine;
import com.easyshell.server.ai.security.SensitiveDataFilter;
import com.easyshell.server.ai.channel.ChannelMessageRouter;
import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.common.exception.WorkflowValidationFailedException;
import com.easyshell.server.common.exception.WorkflowVersionConflictException;
import com.easyshell.server.model.dto.WorkflowCreateRequest;
import com.easyshell.server.model.dto.WorkflowDraftSaveRequest;
import com.easyshell.server.model.entity.Workflow;
import com.easyshell.server.model.entity.WorkflowApproval;
import com.easyshell.server.model.entity.WorkflowRun;
import com.easyshell.server.model.entity.WorkflowVersion;
import com.easyshell.server.model.vo.WorkflowVersionConflictVO;
import com.easyshell.server.model.vo.WorkflowVersionVO;
import com.easyshell.server.repository.WorkflowApprovalRepository;
import com.easyshell.server.repository.WorkflowNodeRunRepository;
import com.easyshell.server.repository.WorkflowRepository;
import com.easyshell.server.repository.WorkflowRunRepository;
import com.easyshell.server.repository.WorkflowVersionRepository;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.service.ClusterService;
import com.easyshell.server.service.TagService;
import com.easyshell.server.service.TaskService;
import com.easyshell.server.workflow.approval.WorkflowApprovalStore;
import com.easyshell.server.workflow.engine.NodeExecutorRegistry;
import com.easyshell.server.workflow.expression.ExpressionEvaluator;
import com.easyshell.server.workflow.graph.DagValidator;
import com.easyshell.server.workflow.model.NodeType;
import com.easyshell.server.workflow.node.ApprovalNodeExecutor;
import com.easyshell.server.workflow.node.ConditionNodeExecutor;
import com.easyshell.server.workflow.node.NotifyNodeExecutor;
import com.easyshell.server.workflow.node.ScriptExecNodeExecutor;
import com.easyshell.server.workflow.node.WaitNodeExecutor;
import com.easyshell.server.workflow.properties.WorkflowProperties;
import com.easyshell.server.workflow.service.WorkflowDefinitionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 定义侧端点的集成测试（真实 H2 + Flyway 的 workflow* 五张表）。
 *
 * <p>覆盖 §6.1 的三件硬事：<b>乐观锁 409 三选一</b>（base_version 条件 UPDATE）、
 * <b>发布门禁</b>（ERROR → 422 带 issues[]；estimated_risk 只存等级与模式串）、
 * <b>归属分页</b>（GET /page 与 GET /runs 的可见范围，含 JPQL 空过滤参数）。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("WorkflowDefinitionService — 草稿乐观锁 / 发布门禁 / 归属分页（真实 H2）")
class WorkflowDefinitionH2Test {

    private static final Long OWNER = 42L;
    private static final Long OTHER = 99L;

    @Autowired
    private WorkflowRepository workflowRepository;
    @Autowired
    private WorkflowVersionRepository versionRepository;
    @Autowired
    private WorkflowRunRepository runRepository;
    @Autowired
    private WorkflowNodeRunRepository nodeRunRepository;
    @Autowired
    private WorkflowApprovalRepository approvalRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @MockitoBean
    private AuditLogService auditLogService;

    private WorkflowDefinitionService service;
    private ObjectMapper objectMapper;

    /** 线性两节点：check(script-exec) -> notify(notify) */
    private static final String VALID_DEFINITION = """
            {"schemaVersion":1,
             "nodes":[
               {"key":"check","type":"script-exec","name":"check","config":{"script":"df -h","parameters":{},"targets":{"mode":"explicit","agentIds":["ag-1"]}}},
               {"key":"notify","type":"notify","name":"notify","config":{"channels":["telegram"],"messageTemplate":"done"}}],
             "edges":[{"id":"e1","from":"check","to":"notify"}]}
            """;

    /** 两节点互指：发布必须被 GRAPH_CYCLE 拦下 */
    private static final String CYCLIC_DEFINITION = """
            {"schemaVersion":1,
             "nodes":[
               {"key":"a","type":"notify","name":"a","config":{"channels":["telegram"],"messageTemplate":"a"}},
               {"key":"b","type":"notify","name":"b","config":{"channels":["telegram"],"messageTemplate":"b"}}],
             "edges":[{"id":"e1","from":"a","to":"b"},{"id":"e2","from":"b","to":"a"}]}
            """;

    /** 带 BANNED 脚本：estimated_risk 必须是 BANNED 且只落模式串 */
    private static final String BANNED_DEFINITION = """
            {"schemaVersion":1,
             "nodes":[
               {"key":"boom","type":"script-exec","name":"boom","config":{"script":"rm -rf /","parameters":{},"targets":{"mode":"explicit","agentIds":["ag-1"]}}}],
             "edges":[]}
            """;

    @BeforeEach
    void setUp() {
        approvalRepository.deleteAll();
        nodeRunRepository.deleteAll();
        runRepository.deleteAll();
        versionRepository.deleteAll();
        workflowRepository.deleteAll();

        objectMapper = new ObjectMapper();
        ExpressionEvaluator expressionEvaluator = new ExpressionEvaluator(objectMapper);
        DagValidator dagValidator = new DagValidator(expressionEvaluator);

        AgenticConfigService config = mock(AgenticConfigService.class);
        lenient().when(config.getStringSet(anyString(), eq(java.util.Set.of()))).thenReturn(java.util.Set.of());
        lenient().when(config.getStringList(anyString(), eq(List.of()))).thenReturn(List.of());
        CommandRiskEngine riskEngine = new CommandRiskEngine(config);
        WorkflowProperties properties = new WorkflowProperties();
        WorkflowApprovalStore approvalStore =
                new WorkflowApprovalStore(approvalRepository, entityManager, transactionManager);

        NodeExecutorRegistry registry = new NodeExecutorRegistry(List.of(
                new ScriptExecNodeExecutor(mock(TaskService.class), mock(ClusterService.class),
                        mock(TagService.class), mock(com.easyshell.server.repository.AgentRepository.class),
                        mock(com.easyshell.server.repository.JobRepository.class),
                        mock(com.easyshell.server.repository.TaskRepository.class), approvalRepository,
                        approvalStore, expressionEvaluator, riskEngine, dagValidator,
                        new SensitiveDataFilter(), objectMapper, properties, transactionManager),
                new ConditionNodeExecutor(expressionEvaluator),
                new NotifyNodeExecutor(mock(ChannelMessageRouter.class), expressionEvaluator,
                        new SensitiveDataFilter()),
                new ApprovalNodeExecutor(approvalStore, expressionEvaluator, properties),
                new WaitNodeExecutor()));

        service = new WorkflowDefinitionService(workflowRepository, versionRepository, dagValidator,
                registry, riskEngine, auditLogService, objectMapper, transactionManager);
    }

    @AfterEach
    void tearDown() {
        approvalRepository.deleteAll();
        nodeRunRepository.deleteAll();
        runRepository.deleteAll();
        versionRepository.deleteAll();
        workflowRepository.deleteAll();
    }

    private Workflow newWorkflow(Long owner, String visibility) {
        WorkflowCreateRequest request = new WorkflowCreateRequest();
        request.setName("disk-cleanup");
        request.setVisibility(visibility);
        return service.create(request, owner, authOf(owner), null);
    }

    private org.springframework.security.core.Authentication authOf(Long userId) {
        org.springframework.security.core.Authentication auth =
                mock(org.springframework.security.core.Authentication.class);
        lenient().when(auth.getPrincipal()).thenReturn(userId);
        lenient().when(auth.getName()).thenReturn("user-" + userId);
        return auth;
    }

    private WorkflowDraftSaveRequest draft(String definitionJson, int baseVersion, String onConflict) {
        WorkflowDraftSaveRequest request = new WorkflowDraftSaveRequest();
        try {
            request.setDefinition(objectMapper.readTree(definitionJson));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        request.setBaseVersion(baseVersion);
        request.setOnConflict(onConflict);
        request.setChangelog("edit");
        return request;
    }

    // ------------------------------------------------------------------
    // 乐观锁 + 409 三选一
    // ------------------------------------------------------------------

    @Test
    @DisplayName("草稿保存：baseVersion 条件 UPDATE，过期 → 409 + 三选一契约")
    void saveDraft_optimisticLock_409WithThreeOptions() {
        Workflow workflow = newWorkflow(OWNER, "private");

        // 首次保存：新建草稿行（version=1, baseVersion=0）
        WorkflowVersion first = service.saveDraft(workflow.getId(), draft(VALID_DEFINITION, 0, null),
                OWNER, authOf(OWNER), null);
        assertThat(first.getVersion()).isEqualTo(1);
        assertThat(first.getBaseVersion()).isZero();

        // 用同一令牌再存：命中条件 UPDATE，baseVersion 推进到 1
        WorkflowVersion second = service.saveDraft(workflow.getId(), draft(VALID_DEFINITION, 0, null),
                OWNER, authOf(OWNER), null);
        assertThat(second.getBaseVersion()).isEqualTo(1);
        assertThat(versionRepository.count()).isEqualTo(1);

        // 令牌过期（别人已经推进过）→ 409 + 三选一
        assertThatThrownBy(() -> service.saveDraft(workflow.getId(), draft(VALID_DEFINITION, 0, null),
                OWNER, authOf(OWNER), null))
                .isInstanceOfSatisfying(WorkflowVersionConflictException.class, e -> {
                    WorkflowVersionConflictVO conflict = e.getConflict();
                    assertThat(conflict.getCode()).isEqualTo("VERSION_CONFLICT");
                    assertThat(conflict.getCurrentBaseVersion()).isEqualTo(1);
                    assertThat(conflict.getOptions())
                            .extracting(WorkflowVersionConflictVO.ConflictOption::getAction)
                            .containsExactly("overwrite", "copy", "reload");
                });
        // 冲突不写库
        assertThat(versionRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("草稿保存：overwrite 覆盖并记 WORKFLOW_OVERWRITE 审计")
    void saveDraft_overwrite_audits() {
        Workflow workflow = newWorkflow(OWNER, "private");
        service.saveDraft(workflow.getId(), draft(VALID_DEFINITION, 0, null), OWNER, authOf(OWNER), null);

        WorkflowVersion overwritten = service.saveDraft(workflow.getId(),
                draft(BANNED_DEFINITION, 0, "overwrite"), OWNER, authOf(OWNER), null);

        assertThat(overwritten.getBaseVersion()).isEqualTo(1);
        assertThat(versionRepository.count()).isEqualTo(1);
        verify(auditLogService).logSync(eq(OWNER), any(), eq("WORKFLOW_OVERWRITE"), eq("workflow"),
                eq(String.valueOf(workflow.getId())), any(), eq(null), eq("success"));
    }

    @Test
    @DisplayName("草稿保存：copy 另存副本，不动当前草稿")
    void saveDraft_copy_createsNewVersion() {
        Workflow workflow = newWorkflow(OWNER, "private");
        service.saveDraft(workflow.getId(), draft(VALID_DEFINITION, 0, null), OWNER, authOf(OWNER), null);

        WorkflowVersion copy = service.saveDraft(workflow.getId(),
                draft(BANNED_DEFINITION, 0, "copy"), OWNER, authOf(OWNER), null);

        assertThat(copy.getVersion()).isEqualTo(2);
        assertThat(versionRepository.count()).isEqualTo(2);
        // 当前草稿仍是 version=1 那份（内容未被 copy 改动）
        Optional<WorkflowVersion> current = versionRepository
                .findFirstByWorkflowIdAndIsValidFalseOrderByVersionDesc(workflow.getId());
        assertThat(current).isPresent();
        assertThat(current.get().getVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("草稿保存：definition 含未知字段 → 400（服务端 schema 权威）")
    void saveDraft_unknownField_is400() {
        Workflow workflow = newWorkflow(OWNER, "private");
        WorkflowDraftSaveRequest request = draft("{\"nodes\":[],\"edges\":[],\"bogus\":1}", 0, null);

        assertThatThrownBy(() -> service.saveDraft(workflow.getId(), request, OWNER, authOf(OWNER), null))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("WORKFLOW_PARAM_INVALID");
                });
    }

    // ------------------------------------------------------------------
    // 校验与发布
    // ------------------------------------------------------------------

    @Test
    @DisplayName("validate：有环定义返回 ERROR issues")
    void validate_reportsCycle() {
        var result = service.validate(json(CYCLIC_DEFINITION));

        assertThat(result.getValid()).isFalse();
        assertThat(result.getIssues())
                .anyMatch(i -> "GRAPH_CYCLE".equals(i.getCode()) && "ERROR".equals(i.getSeverity()));
    }

    @Test
    @DisplayName("publish：合法草稿发布成功并回写 current_version_id")
    void publish_ok() {
        Workflow workflow = newWorkflow(OWNER, "private");
        service.saveDraft(workflow.getId(), draft(VALID_DEFINITION, 0, null), OWNER, authOf(OWNER), null);

        WorkflowVersion published = service.publish(workflow.getId(), "first release", OWNER,
                authOf(OWNER), null);

        assertThat(published.getIsValid()).isTrue();
        assertThat(published.getPublishedBy()).isEqualTo(OWNER);
        assertThat(published.getPublishedAt()).isNotNull();
        assertThat(published.getChangelog()).isEqualTo("first release");
        assertThat(published.getEstimatedRisk()).isEqualTo("LOW");
        Workflow reloaded = workflowRepository.findById(workflow.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo("published");
        assertThat(reloaded.getCurrentVersionId()).isEqualTo(published.getId());
        // 高危事件同步审计（C-13）
        verify(auditLogService).logSync(eq(OWNER), any(), eq("WORKFLOW_PUBLISH"), eq("workflow"),
                eq(String.valueOf(workflow.getId())), any(), eq(null), eq("success"));
    }

    @Test
    @DisplayName("publish：ERROR → 422 + issues[]，且不发布")
    void publish_withErrors_is422() {
        Workflow workflow = newWorkflow(OWNER, "private");
        service.saveDraft(workflow.getId(), draft(CYCLIC_DEFINITION, 0, null), OWNER, authOf(OWNER), null);

        assertThatThrownBy(() -> service.publish(workflow.getId(), null, OWNER, authOf(OWNER), null))
                .isInstanceOfSatisfying(WorkflowValidationFailedException.class, e -> {
                    assertThat(e.getResult().getValid()).isFalse();
                    assertThat(e.getResult().getIssues())
                            .anyMatch(i -> "GRAPH_CYCLE".equals(i.getCode()));
                });

        Optional<WorkflowVersion> stillDraft = versionRepository
                .findFirstByWorkflowIdAndIsValidFalseOrderByVersionDesc(workflow.getId());
        assertThat(stillDraft).isPresent();
        assertThat(stillDraft.get().getIsValid()).isFalse();
    }

    @Test
    @DisplayName("publish：BANNED 脚本的 estimated_risk 只落等级与模式串")
    void publish_bannedScript_estimatedRiskHasNoCommandText() {
        Workflow workflow = newWorkflow(OWNER, "private");
        service.saveDraft(workflow.getId(), draft(BANNED_DEFINITION, 0, null), OWNER, authOf(OWNER), null);

        var validated = service.validate(json(BANNED_DEFINITION));
        assertThat(validated.getEstimatedRisk()).isEqualTo("BANNED");
        assertThat(validated.getRiskMatches()).isNotEmpty();

        WorkflowVersion published = service.publish(workflow.getId(), null, OWNER, authOf(OWNER), null);
        assertThat(published.getEstimatedRisk()).isEqualTo("BANNED");
        // estimated_risk 只存等级；模式串只存正则/模式，不存脚本原文
        assertThat(published.getEstimatedRisk()).doesNotContain("rm -rf /");
    }

    @Test
    @DisplayName("publish：没有草稿 → 400")
    void publish_withoutDraft_is400() {
        Workflow workflow = newWorkflow(OWNER, "private");

        assertThatThrownBy(() -> service.publish(workflow.getId(), null, OWNER, authOf(OWNER), null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).contains("没有可发布的草稿"));
    }

    @Test
    @DisplayName("版本列表与版本定义读取")
    void versions_listAndDefinition() {
        Workflow workflow = newWorkflow(OWNER, "private");
        service.saveDraft(workflow.getId(), draft(VALID_DEFINITION, 0, null), OWNER, authOf(OWNER), null);
        WorkflowVersion published = service.publish(workflow.getId(), null, OWNER, authOf(OWNER), null);
        service.saveDraft(workflow.getId(), draft(VALID_DEFINITION, 0, null), OWNER, authOf(OWNER), null);

        List<WorkflowVersionVO> versions = service.listVersions(workflow.getId());
        assertThat(versions).hasSize(2);

        com.easyshell.server.model.vo.WorkflowVersionDetailVO detail =
                service.versionDefinition(workflow.getId(), published.getId());
        assertThat(detail.getDefinition().get("nodes")).hasSize(2);
        // 平铺契约：摘要字段与 definition 同级
        assertThat(detail.getId()).isEqualTo(published.getId());
        assertThat(detail.getVersion()).isEqualTo(published.getVersion());
        assertThat(detail.getIsValid()).isTrue();

        assertThatThrownBy(() -> service.versionDefinition(workflow.getId(), 999999L))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(404));
    }

    // ------------------------------------------------------------------
    // 归属分页（GET /page 与 GET /runs 的可见范围）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /page：非管理员只看自己的+公开的；管理员看全部；status 可过滤")
    void page_scopesByVisibilityAndRole() {
        newWorkflow(OWNER, "private");
        newWorkflow(OTHER, "public");
        newWorkflow(OTHER, "private");

        Page<Workflow> asOwner = service.pageForUser(null, null, null, OWNER, PageRequest.of(0, 20));
        assertThat(asOwner.getContent()).hasSize(2);   // 自己的 + 对方的 public

        Page<Workflow> asOther = service.pageForUser(null, null, null, OTHER, PageRequest.of(0, 20));
        assertThat(asOther.getContent()).hasSize(2);   // 自己的两个

        Page<Workflow> asAdmin = service.pageForAdmin(null, null, null, PageRequest.of(0, 20));
        assertThat(asAdmin.getContent()).hasSize(3);

        Page<Workflow> published = service.pageForAdmin("draft", null, null, PageRequest.of(0, 20));
        assertThat(published.getContent()).hasSize(3);
        Page<Workflow> archived = service.pageForAdmin("archived", null, null, PageRequest.of(0, 20));
        assertThat(archived.getContent()).isEmpty();
    }

    @Test
    @DisplayName("GET /runs：可见范围 = 自己触发的 + 自己工作流上的；admin 全部")
    void runs_visibilityScope() {
        Workflow mine = newWorkflow(OWNER, "private");
        Workflow theirs = newWorkflow(OTHER, "public");

        WorkflowRun runA = newRun("wfr_a", mine.getId(), OWNER);
        WorkflowRun runB = newRun("wfr_b", theirs.getId(), OTHER);
        WorkflowRun runC = newRun("wfr_c", theirs.getId(), OWNER);

        Page<WorkflowRun> asOwner = runRepository.findVisibleToUser(OWNER, null, null, PageRequest.of(0, 20));
        assertThat(asOwner.getContent()).extracting(WorkflowRun::getId)
                .containsExactlyInAnyOrder("wfr_a", "wfr_c");

        // OTHER 看不到 runA：既不是触发人，也不拥有 runA 所属的私有工作流
        Page<WorkflowRun> asOther = runRepository.findVisibleToUser(OTHER, null, null, PageRequest.of(0, 20));
        assertThat(asOther.getContent()).extracting(WorkflowRun::getId)
                .containsExactlyInAnyOrder("wfr_b", "wfr_c");

        Page<WorkflowRun> asAdmin = runRepository.findAllFiltered(null, null, PageRequest.of(0, 20));
        assertThat(asAdmin.getContent()).hasSize(3);

        // 过滤参数：status / workflowId（HQL 参数化 IS NULL）
        assertThat(runRepository.findVisibleToUser(OWNER, "running", null, PageRequest.of(0, 20))
                .getContent()).hasSize(2);
        assertThat(runRepository.findVisibleToUser(OWNER, null, mine.getId(), PageRequest.of(0, 20))
                .getContent()).extracting(WorkflowRun::getId).containsExactly("wfr_a");
    }

    @Test
    @DisplayName("DELETE /{id}：软删为 archived 且幂等")
    void archive_isIdempotent() {
        Workflow workflow = newWorkflow(OWNER, "private");

        Workflow archived = service.archive(workflow.getId(), OWNER, authOf(OWNER), null);
        assertThat(archived.getStatus()).isEqualTo("archived");

        // 二次软删：状态不变、不重复写审计
        service.archive(workflow.getId(), OWNER, authOf(OWNER), null);
        assertThat(workflowRepository.findById(workflow.getId()).orElseThrow().getStatus())
                .isEqualTo("archived");
        verify(auditLogService, org.mockito.Mockito.times(1))
                .log(eq(OWNER), any(), eq("WORKFLOW_ARCHIVE"), eq("workflow"),
                        eq(String.valueOf(workflow.getId())), any(), eq(null), eq("success"));
    }

    @Test
    @DisplayName("updateMeta：只改元信息，不动定义")
    void updateMeta_onlyTouchesMeta() {
        Workflow workflow = newWorkflow(OWNER, "private");
        service.saveDraft(workflow.getId(), draft(VALID_DEFINITION, 0, null), OWNER, authOf(OWNER), null);

        com.easyshell.server.model.dto.WorkflowUpdateRequest request =
                new com.easyshell.server.model.dto.WorkflowUpdateRequest();
        request.setName("renamed");
        request.setVisibility("public");
        Workflow updated = service.updateMeta(workflow.getId(), request, OWNER, authOf(OWNER), null);

        assertThat(updated.getName()).isEqualTo("renamed");
        assertThat(updated.getVisibility()).isEqualTo("public");
        // 定义未被触碰
        assertThat(versionRepository.findFirstByWorkflowIdAndIsValidFalseOrderByVersionDesc(workflow.getId()))
                .isPresent();
    }

    @Test
    @DisplayName("节点类型枚举：M1 五种，未知类型返回 null")
    void nodeType_coverage() {
        assertThat(NodeType.fromCode("script-exec")).isEqualTo(NodeType.SCRIPT_EXEC);
        assertThat(NodeType.fromCode("nope")).isNull();
    }

    // ------------------------------------------------------------------

    private JsonNode json(String definitionJson) {
        try {
            return objectMapper.readTree(definitionJson);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private WorkflowRun newRun(String id, Long workflowId, Long startedBy) {
        WorkflowRun run = new WorkflowRun();
        run.setId(id);
        run.setWorkflowId(workflowId);
        run.setVersionId(1L);
        run.setVersionNo(1);
        run.setStatus("running");
        run.setStartedBy(startedBy);
        return runRepository.saveAndFlush(run);
    }
}
