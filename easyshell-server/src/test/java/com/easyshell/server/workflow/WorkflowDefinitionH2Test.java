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
import com.easyshell.server.workflow.approval.WorkflowApprovalAttemptBinding;
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
import org.springframework.context.annotation.Import;
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
@Import({WorkflowApprovalStore.class, WorkflowApprovalAttemptBinding.class})
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
    private com.easyshell.server.repository.ScriptRepository scriptRepository;
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
    @Autowired
    private WorkflowApprovalAttemptBinding approvalAttemptBinding;

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
                        mock(com.easyshell.server.repository.WorkflowNodeRunRepository.class),
                        runRepository, entityManager, approvalStore, expressionEvaluator, riskEngine, dagValidator,
                        new SensitiveDataFilter(), objectMapper, properties, transactionManager),
                new ConditionNodeExecutor(expressionEvaluator),
                new NotifyNodeExecutor(mock(ChannelMessageRouter.class), expressionEvaluator,
                        new SensitiveDataFilter()),
                new ApprovalNodeExecutor(approvalStore, new com.easyshell.server.workflow.approval.WorkflowApprovalSuspensionService(
                        approvalAttemptBinding, transactionManager), expressionEvaluator, properties,
                        mock(com.easyshell.server.workflow.service.WorkflowRunProvenanceService.class)),
                new WaitNodeExecutor()));

        var scriptUsers = mock(com.easyshell.server.repository.UserRepository.class);
        var owner = new com.easyshell.server.model.entity.User();
        owner.setId(OWNER);
        lenient().when(scriptUsers.findById(OWNER)).thenReturn(java.util.Optional.of(owner));
        service = new WorkflowDefinitionService(workflowRepository, versionRepository,
                new com.easyshell.server.security.ScriptAccess(scriptRepository, scriptUsers), dagValidator,
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
        request.setExpectedAbsent(true);
        request.setOnConflict(onConflict);
        request.setChangelog("edit");
        return request;
    }

    private WorkflowDraftSaveRequest draftFor(String definition, WorkflowVersion snapshot, String mode) {
        WorkflowDraftSaveRequest request = draft(definition, snapshot.getBaseVersion(), mode);
        request.setExpectedAbsent(false);
        request.setDraftId(snapshot.getId());
        request.setDefinitionSha256(snapshot.getDefinitionSha256());
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
        WorkflowVersion second = service.saveDraft(workflow.getId(), draftFor(VALID_DEFINITION, first, null),
                OWNER, authOf(OWNER), null);
        assertThat(second.getBaseVersion()).isEqualTo(1);
        assertThat(versionRepository.count()).isEqualTo(1);

        // 令牌过期（别人已经推进过）→ 409 + 三选一
        assertThatThrownBy(() -> service.saveDraft(workflow.getId(), draftFor(VALID_DEFINITION, first, null),
                OWNER, authOf(OWNER), null))
                .isInstanceOfSatisfying(WorkflowVersionConflictException.class, e -> {
                    WorkflowVersionConflictVO conflict = e.getConflict();
                    assertThat(conflict.getCode()).isEqualTo("VERSION_CONFLICT");
                    assertThat(conflict.getCurrentBaseVersion()).isEqualTo(1);
                    assertThat(conflict.getDraftVersionId()).isEqualTo(second.getId());
                    assertThat(conflict.getCurrentDefinitionSha256()).isEqualTo(second.getDefinitionSha256());
                    assertThat(conflict.getCurrentDraftExists()).isTrue();
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
        WorkflowVersion initial = service.saveDraft(workflow.getId(), draft(VALID_DEFINITION, 0, null), OWNER, authOf(OWNER), null);

        WorkflowVersion overwritten = service.saveDraft(workflow.getId(),
                draftFor(BANNED_DEFINITION, initial, "overwrite"), OWNER, authOf(OWNER), null);

        assertThat(overwritten.getBaseVersion()).isEqualTo(1);
        assertThat(versionRepository.count()).isEqualTo(1);
        verify(auditLogService).logSync(eq(OWNER), any(), eq("WORKFLOW_OVERWRITE"), eq("workflow"),
                eq(String.valueOf(workflow.getId())), any(), eq(null), eq("success"));
    }

    @Test
    @DisplayName("草稿保存：copy 另存副本，不动当前草稿")
    void saveDraft_copy_createsNewVersion() {
        Workflow workflow = newWorkflow(OWNER, "private");
        WorkflowVersion initial = service.saveDraft(workflow.getId(), draft(VALID_DEFINITION, 0, null), OWNER, authOf(OWNER), null);

        WorkflowVersion copy = service.saveDraft(workflow.getId(),
                draftFor(BANNED_DEFINITION, initial, "copy"), OWNER, authOf(OWNER), null);

        assertThat(copy.getVersion()).isEqualTo(2);
        assertThat(versionRepository.count()).isEqualTo(2);
        // 副本成为当前草稿；原 version=1 保持不变。
        Optional<WorkflowVersion> current = versionRepository
                .findFirstByWorkflowIdAndIsValidFalseOrderByVersionDesc(workflow.getId());
        assertThat(current).isPresent();
        assertThat(current.get().getVersion()).isEqualTo(2);
        assertThat(versionRepository.findById(initial.getId()).orElseThrow().getDefinitionSha256())
                .isEqualTo(initial.getDefinitionSha256());
    }

    @Test
    void copyRequiresExistingUnpublishedSourceFromSameWorkflowButAllowsStaleSnapshot() {
        Workflow workflow = newWorkflow(OWNER, "private");
        Workflow other = newWorkflow(OWNER, "private");
        WorkflowVersion source = service.saveDraft(workflow.getId(), draft(VALID_DEFINITION, 0, null), OWNER, authOf(OWNER), null);
        WorkflowVersion foreign = service.saveDraft(other.getId(), draft(VALID_DEFINITION, 0, null), OWNER, authOf(OWNER), null);
        WorkflowVersion updated = service.saveDraft(workflow.getId(), draftFor(BANNED_DEFINITION, source, null), OWNER, authOf(OWNER), null);
        WorkflowDraftSaveRequest nonexistent = draftFor(VALID_DEFINITION, source, "copy");
        nonexistent.setDraftId(Long.MAX_VALUE);
        for (WorkflowDraftSaveRequest invalid : List.of(draftFor(VALID_DEFINITION, foreign, "copy"), nonexistent)) {
            assertThatThrownBy(() -> service.saveDraft(workflow.getId(), invalid, OWNER, authOf(OWNER), null))
                    .isInstanceOfSatisfying(WorkflowVersionConflictException.class, e -> {
                        assertThat(e.getConflict().getCode()).isEqualTo("VERSION_CONFLICT");
                        assertThat(e.getConflict().getDraftVersionId()).isEqualTo(updated.getId());
                    });
        }
        assertThat(versionRepository.findByWorkflowIdOrderByVersionDesc(workflow.getId())).hasSize(1);
        WorkflowVersion copy = service.saveDraft(workflow.getId(), draftFor(VALID_DEFINITION, source, "copy"), OWNER, authOf(OWNER), null);
        assertThat(copy.getId()).isNotEqualTo(source.getId());
        assertThat(copy.getVersion()).isGreaterThan(updated.getVersion());
        assertThat(versionRepository.findFirstByWorkflowIdAndIsValidFalseOrderByVersionDesc(workflow.getId())
                .orElseThrow().getId()).isEqualTo(copy.getId());
        assertThat(versionRepository.findById(source.getId()).orElseThrow().getDefinitionSha256())
                .isEqualTo(updated.getDefinitionSha256());
    }

    @Test
    void copiedDraftRejectsOldIdentityAndStaleSha() {
        Workflow workflow = newWorkflow(OWNER, "private");
        WorkflowVersion x = service.saveDraft(workflow.getId(), draft(VALID_DEFINITION, 0, null), OWNER, authOf(OWNER), null);
        WorkflowVersion y = service.saveDraft(workflow.getId(), draftFor(BANNED_DEFINITION, x, "copy"), OWNER, authOf(OWNER), null);
        assertThat(y.getId()).isNotEqualTo(x.getId());
        for (String mode : List.of("reject", "overwrite")) {
            assertThatThrownBy(() -> service.saveDraft(workflow.getId(), draftFor(VALID_DEFINITION, x, mode), OWNER, authOf(OWNER), null))
                    .isInstanceOfSatisfying(WorkflowVersionConflictException.class, e -> {
                        assertThat(e.getConflict().getDraftVersionId()).isEqualTo(y.getId());
                        assertThat(e.getConflict().getCurrentBaseVersion()).isEqualTo(y.getBaseVersion());
                        assertThat(e.getConflict().getCurrentDefinitionSha256()).isEqualTo(y.getDefinitionSha256());
                        assertThat(e.getConflict().getCurrentDraftExists()).isTrue();
                    });
            WorkflowDraftSaveRequest wrongSha = draftFor(VALID_DEFINITION, y, mode);
            wrongSha.setDefinitionSha256("stale");
            assertThatThrownBy(() -> service.saveDraft(workflow.getId(), wrongSha, OWNER, authOf(OWNER), null))
                    .isInstanceOf(WorkflowVersionConflictException.class);
        }
        assertThat(versionRepository.findById(y.getId()).orElseThrow().getDefinitionSha256()).isEqualTo(y.getDefinitionSha256());
    }

    @Test
    void conflictResponseTokenCanTargetConfirmedOverwrite() throws Exception {
        Workflow workflow = newWorkflow(OWNER, "private");
        WorkflowVersion x = service.saveDraft(workflow.getId(), draft(VALID_DEFINITION, 0, null), OWNER, authOf(OWNER), null);
        WorkflowVersion y = service.saveDraft(workflow.getId(), draftFor(BANNED_DEFINITION, x, "copy"), OWNER, authOf(OWNER), null);
        WorkflowVersionConflictVO conflict;
        try {
            service.saveDraft(workflow.getId(), draftFor(VALID_DEFINITION, x, null), OWNER, authOf(OWNER), null);
            throw new AssertionError("expected conflict");
        } catch (WorkflowVersionConflictException e) {
            conflict = e.getConflict();
        }
        var json = objectMapper.valueToTree(conflict);
        assertThat(json.path("code").asText()).isEqualTo("VERSION_CONFLICT");
        assertThat(json.path("message").asText()).isNotBlank();
        assertThat(json.path("draftVersionId").asLong()).isEqualTo(y.getId());
        assertThat(json.path("currentBaseVersion").asInt()).isEqualTo(y.getBaseVersion());
        assertThat(json.path("currentDefinitionSha256").asText()).isEqualTo(y.getDefinitionSha256());
        assertThat(json.path("currentDraftExists").asBoolean()).isTrue();
        assertThat(json.path("options").size()).isEqualTo(3);
        WorkflowDraftSaveRequest overwrite = draft(VALID_DEFINITION, conflict.getCurrentBaseVersion(), "overwrite");
        overwrite.setExpectedAbsent(false);
        overwrite.setDraftId(conflict.getDraftVersionId());
        overwrite.setDefinitionSha256(conflict.getCurrentDefinitionSha256());
        WorkflowVersion result = service.saveDraft(workflow.getId(), overwrite, OWNER, authOf(OWNER), null);
        assertThat(result.getId()).isEqualTo(y.getId());
        assertThat(result.getBaseVersion()).isEqualTo(y.getBaseVersion() + 1);
        assertThat(versionRepository.findById(x.getId()).orElseThrow().getDefinitionSha256()).isEqualTo(x.getDefinitionSha256());
    }

    @Test
    void creationRequiresExplicitAbsenceAndLegacyUpdateConflicts() {
        Workflow workflow = newWorkflow(OWNER, "private");
        WorkflowDraftSaveRequest legacy = draft(VALID_DEFINITION, 0, null);
        legacy.setExpectedAbsent(null);
        assertThatThrownBy(() -> service.saveDraft(workflow.getId(), legacy, OWNER, authOf(OWNER), null))
                .isInstanceOfSatisfying(WorkflowVersionConflictException.class, e -> {
                    assertThat(e.getConflict().getCurrentDraftExists()).isFalse();
                    assertThat(e.getConflict().getDraftVersionId()).isNull();
                    assertThat(e.getConflict().getCurrentBaseVersion()).isNull();
                    assertThat(e.getConflict().getCurrentDefinitionSha256()).isNull();
                    assertThat(e.getConflict().getOptions()).hasSize(3);
                });
        WorkflowDraftSaveRequest wrongBase = draft(VALID_DEFINITION, 1, null);
        assertThatThrownBy(() -> service.saveDraft(workflow.getId(), wrongBase, OWNER, authOf(OWNER), null))
                .isInstanceOf(WorkflowVersionConflictException.class);
        service.saveDraft(workflow.getId(), draft(VALID_DEFINITION, 0, null), OWNER, authOf(OWNER), null);
        assertThatThrownBy(() -> service.saveDraft(workflow.getId(), legacy, OWNER, authOf(OWNER), null))
                .isInstanceOf(WorkflowVersionConflictException.class);
    }

    @Test
    void concurrentSavesOfSameSnapshotOnlyOneSucceeds() throws Exception {
        Workflow workflow = newWorkflow(OWNER, "private");
        WorkflowVersion snapshot = service.saveDraft(workflow.getId(), draft(VALID_DEFINITION, 0, null), OWNER, authOf(OWNER), null);
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<WorkflowVersionConflictVO> save = () -> {
                start.await();
                try {
                    service.saveDraft(workflow.getId(), draftFor(BANNED_DEFINITION, snapshot, "overwrite"), OWNER, authOf(OWNER), null);
                    return null;
                } catch (WorkflowVersionConflictException conflict) {
                    return conflict.getConflict();
                }
            };
            var a = pool.submit(save);
            var b = pool.submit(save);
            start.countDown();
            WorkflowVersionConflictVO first = a.get();
            WorkflowVersionConflictVO second = b.get();
            assertThat((first == null) ^ (second == null)).isTrue();
            WorkflowVersionConflictVO conflict = first != null ? first : second;
            WorkflowVersion stored = versionRepository.findById(snapshot.getId()).orElseThrow();
            assertThat(stored.getBaseVersion()).isEqualTo(snapshot.getBaseVersion() + 1);
            assertThat(stored.getDefinitionJson()).isNotEqualTo(snapshot.getDefinitionJson());
            assertThat(conflict.getCode()).isEqualTo("VERSION_CONFLICT");
            assertThat(conflict.getDraftVersionId()).isEqualTo(stored.getId());
            assertThat(conflict.getCurrentBaseVersion()).isEqualTo(stored.getBaseVersion());
            assertThat(conflict.getCurrentDefinitionSha256()).isEqualTo(stored.getDefinitionSha256());
        } finally {
            pool.shutdownNow();
        }
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

        WorkflowVersion published = service.publish(workflow.getId(), publishRequest(workflow.getId(), "first release"), OWNER,
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
    void publishedVersionCannotBeChangedByStaleDraftSave() {
        Workflow workflow = newWorkflow(OWNER, "private");
        WorkflowVersion draft = service.saveDraft(workflow.getId(), draft(VALID_DEFINITION, 0, null),
                OWNER, authOf(OWNER), null);
        WorkflowVersion published = service.publish(workflow.getId(), publishRequest(workflow.getId(), "release"), OWNER, authOf(OWNER), null);
        int updated = new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status ->
                versionRepository.saveDraftIfBaseVersionMatches(draft.getId(), "{}", "changed", "changed", 0, draft.getDefinitionSha256()));
        assertThat(updated).isZero();
        WorkflowVersion stored = versionRepository.findById(draft.getId()).orElseThrow();
        assertThat(stored.getDefinitionJson()).isEqualTo(published.getDefinitionJson());
        assertThat(stored.getDefinitionSha256()).isEqualTo(published.getDefinitionSha256());
        assertThat(stored.getChangelog()).isEqualTo("release");
    }

    @Test
    void publishCasRejectsEachChangedSnapshotComponent() {
        Workflow workflow = newWorkflow(OWNER, "private");
        WorkflowVersion draft = service.saveDraft(workflow.getId(), draft(VALID_DEFINITION, 0, null),
                OWNER, authOf(OWNER), null);
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        for (int mismatch = 0; mismatch < 2; mismatch++) {
            int index = mismatch;
            int updated = tx.execute(status -> versionRepository.publishValidatedDraftAtomically(draft.getId(),
                    java.time.LocalDateTime.now(), OWNER, "LOW", "release",
                    index == 0 ? 1 : draft.getBaseVersion(),
                    index == 1 ? "different" : draft.getDefinitionSha256(),
                    draft.getDefinitionJson(), draft.getDefinitionSha256()));
            assertThat(updated).isZero();
        }
        assertThat(versionRepository.findById(draft.getId()).orElseThrow().getIsValid()).isFalse();
    }

    @Test
    void librarySnapshotRejectsForgeryAndSurvivesScriptChange() throws Exception {
        var script = new com.easyshell.server.model.entity.Script();
        script.setName("snapshot-test-" + System.nanoTime());
        script.setContent("df -h");
        script.setCreatedBy(OWNER);
        script.setIsPublic(false);
        script = scriptRepository.saveAndFlush(script);
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(script.getContent().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var definition = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(VALID_DEFINITION);
        var config = (com.fasterxml.jackson.databind.node.ObjectNode) definition.path("nodes").get(0).path("config");
        config.putObject("scriptSource").put("kind", "library-v1").put("scriptId", script.getId())
                .put("version", 1).put("sha256", hash);
        Workflow workflow = newWorkflow(OWNER, "private");
        config.put("script", "forged");
        WorkflowVersion initial = service.saveDraft(workflow.getId(), draft(definition.toString(), 0, null), OWNER, authOf(OWNER), null);
        assertThatThrownBy(() -> service.publish(workflow.getId(), publishRequest(workflow.getId(), null), OWNER, authOf(OWNER), null))
                .isInstanceOf(BusinessException.class);
        config.put("script", "df -h");
        service.saveDraft(workflow.getId(), draftFor(definition.toString(), initial, null), OWNER, authOf(OWNER), null);
        WorkflowVersion published = service.publish(workflow.getId(), publishRequest(workflow.getId(), null), OWNER, authOf(OWNER), null);
        script.setContent("changed");
        script.setVersion(2);
        scriptRepository.saveAndFlush(script);
        assertThat(versionRepository.findById(published.getId()).orElseThrow().getDefinitionJson())
                .contains("df -h").doesNotContain("changed");
        scriptRepository.deleteById(script.getId());
    }

    @Test
    void librarySnapshotHidesPrivateScriptFromNonOwnerDespiteAdminToken() throws Exception {
        var script = new com.easyshell.server.model.entity.Script();
        script.setName("private-snapshot-" + System.nanoTime());
        script.setContent("df -h");
        script.setCreatedBy(OWNER + 1);
        script.setIsPublic(false);
        script = scriptRepository.saveAndFlush(script);
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(script.getContent().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var definition = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(VALID_DEFINITION);
        var config = (com.fasterxml.jackson.databind.node.ObjectNode) definition.path("nodes").get(0).path("config");
        config.putObject("scriptSource").put("kind", "library-v1").put("scriptId", script.getId())
                .put("version", 1).put("sha256", hash);
        Workflow workflow = newWorkflow(OWNER, "private");
        service.saveDraft(workflow.getId(), draft(definition.toString(), 0, null), OWNER, authOf(OWNER), null);
        var adminToken = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(OWNER, "",
                java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));
        assertThatThrownBy(() -> service.publish(workflow.getId(), publishRequest(workflow.getId(), null), OWNER, adminToken, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(404));
        scriptRepository.deleteById(script.getId());
    }

    @Test
    void strictAiCannotBeRemovedOrChangedByAnyDraftSaveMode() {
        Workflow workflow = newWorkflow(OWNER, "private");
        String strict = """
                {"schemaVersion":1,"nodes":[{"key":"ai","type":"llm","config":{
                "outputMode":"json_schema_v1","jsonSchema":"{\\"type\\":\\"object\\"}"}}],"edges":[]}
                """;
        WorkflowVersion initial = service.saveDraft(workflow.getId(), draft(strict, 0, null), OWNER, authOf(OWNER), null);
        for (String mode : List.of("reject", "overwrite", "copy")) {
            assertThatThrownBy(() -> service.saveDraft(workflow.getId(),
                    draftFor(VALID_DEFINITION, initial, mode), OWNER, authOf(OWNER), null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("STRICT_AI_CHANGE_FORBIDDEN");
        }
        String changed = strict.replace("object", "string");
        assertThatThrownBy(() -> service.saveDraft(workflow.getId(), draftFor(changed, initial, "overwrite"),
                OWNER, authOf(OWNER), null)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("STRICT_AI_CHANGE_FORBIDDEN");
    }

    @Test
    void concurrentStrictCreationAndLegacyOverwriteCannotEraseStrictDraft() throws Exception {
        Workflow workflow = newWorkflow(OWNER, "private");
        String strict = """
                {"schemaVersion":1,"nodes":[{"key":"ai","type":"llm","config":{
                "outputMode":"json_schema_v1","jsonSchema":"{\\"type\\":\\"object\\"}"}}],"edges":[]}
                """;
        WorkflowVersion initial = service.saveDraft(workflow.getId(), draft(strict, 0, null), OWNER, authOf(OWNER), null);
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> {
                start.await();
                service.saveDraft(workflow.getId(), draftFor(strict, initial, "overwrite"), OWNER, authOf(OWNER), null);
                return true;
            });
            var second = pool.submit(() -> {
                start.await();
                try {
                    service.saveDraft(workflow.getId(), draft(VALID_DEFINITION, 0, null),
                            OWNER, authOf(OWNER), null);
                    return false;
                } catch (WorkflowVersionConflictException expected) {
                    assertThat(expected.getConflict().getCurrentDraftExists()).isTrue();
                    assertThat(expected.getConflict().getDraftVersionId()).isEqualTo(initial.getId());
                    return true;
                }
            });
            start.countDown();
            assertThat(first.get()).isTrue();
            assertThat(second.get()).isTrue();
            assertThat(versionRepository.findFirstByWorkflowIdAndIsValidFalseOrderByVersionDesc(workflow.getId())
                    .orElseThrow().getDefinitionJson()).contains("json_schema_v1");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void publishAuditFailureRollsBackVersionAndWorkflow() {
        Workflow workflow = newWorkflow(OWNER, "private");
        WorkflowVersion draft = service.saveDraft(workflow.getId(), draft(VALID_DEFINITION, 0, null),
                OWNER, authOf(OWNER), null);
        org.mockito.Mockito.doThrow(new IllegalStateException("audit failed")).when(auditLogService)
                .logSync(eq(OWNER), any(), eq("WORKFLOW_PUBLISH"), eq("workflow"),
                        eq(String.valueOf(workflow.getId())), any(), eq(null), eq("success"));
        assertThatThrownBy(() -> service.publish(workflow.getId(), publishRequest(workflow.getId(), "release"), OWNER, authOf(OWNER), null))
                .isInstanceOf(IllegalStateException.class);
        WorkflowVersion stored = versionRepository.findById(draft.getId()).orElseThrow();
        assertThat(stored.getIsValid()).isFalse();
        assertThat(stored.getChangelog()).isEqualTo("edit");
        assertThat(workflowRepository.findById(workflow.getId()).orElseThrow().getCurrentVersionId()).isNull();
    }

    @Test
    @DisplayName("publish：ERROR → 422 + issues[]，且不发布")
    void publish_withErrors_is422() {
        Workflow workflow = newWorkflow(OWNER, "private");
        service.saveDraft(workflow.getId(), draft(CYCLIC_DEFINITION, 0, null), OWNER, authOf(OWNER), null);

        assertThatThrownBy(() -> service.publish(workflow.getId(), publishRequest(workflow.getId(), null), OWNER, authOf(OWNER), null))
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

        WorkflowVersion published = service.publish(workflow.getId(), publishRequest(workflow.getId(), null), OWNER, authOf(OWNER), null);
        assertThat(published.getEstimatedRisk()).isEqualTo("BANNED");
        // estimated_risk 只存等级；模式串只存正则/模式，不存脚本原文
        assertThat(published.getEstimatedRisk()).doesNotContain("rm -rf /");
    }

    @Test
    void publishRequiresExactDraftToken() {
        Workflow workflow = newWorkflow(OWNER, "private");
        WorkflowVersion draft = service.saveDraft(workflow.getId(), draft(VALID_DEFINITION, 0, null),
                OWNER, authOf(OWNER), null);
        for (int field = 0; field < 3; field++) {
            var request = publishRequest(workflow.getId(), null);
            if (field == 0) request.setDraftId(draft.getId() + 1);
            if (field == 1) request.setBaseVersion(draft.getBaseVersion() + 1);
            if (field == 2) request.setDefinitionSha256("wrong");
            assertThatThrownBy(() -> service.publish(workflow.getId(), request, OWNER, authOf(OWNER), null))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(409));
        }
        assertThat(versionRepository.findById(draft.getId()).orElseThrow().getIsValid()).isFalse();
        assertThat(workflowRepository.findById(workflow.getId()).orElseThrow().getCurrentVersionId()).isNull();
    }

    @Test
    @DisplayName("publish：没有草稿 → 409")
    void publish_withoutDraft_is409() {
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
        WorkflowVersion published = service.publish(workflow.getId(), publishRequest(workflow.getId(), null), OWNER, authOf(OWNER), null);
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
    private com.easyshell.server.model.dto.WorkflowPublishRequest publishRequest(Long workflowId, String changelog) {
        var draft = versionRepository.findFirstByWorkflowIdAndIsValidFalseOrderByVersionDesc(workflowId).orElseThrow();
        var request = new com.easyshell.server.model.dto.WorkflowPublishRequest();
        request.setDraftId(draft.getId());
        request.setBaseVersion(draft.getBaseVersion());
        request.setDefinitionSha256(draft.getDefinitionSha256());
        request.setChangelog(changelog);
        return request;
    }
}
