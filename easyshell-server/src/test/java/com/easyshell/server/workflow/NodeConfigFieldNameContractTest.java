package com.easyshell.server.workflow;

import com.easyshell.server.ai.config.AgenticConfigService;
import com.easyshell.server.ai.risk.CommandRiskEngine;
import com.easyshell.server.ai.security.SensitiveDataFilter;
import com.easyshell.server.ai.channel.ChannelMessageRouter;
import com.easyshell.server.common.exception.WorkflowValidationFailedException;
import com.easyshell.server.model.dto.WorkflowCreateRequest;
import com.easyshell.server.model.dto.WorkflowDraftSaveRequest;
import com.easyshell.server.model.entity.Workflow;
import com.easyshell.server.model.entity.WorkflowVersion;
import com.easyshell.server.repository.AgentRepository;
import com.easyshell.server.repository.JobRepository;
import com.easyshell.server.repository.TaskRepository;
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
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * 前端配置面板 → 服务端发布门禁的<b>字段名契约</b>回归（真机缺陷：UI 建不出能发布的工作流）。
 *
 * <p>背景：`NodeFormPanel` 曾写出 `config.scriptContent` / `config.channel` / `config.message`，
 * 而执行器读的是 `script` / `channels` / `messageTemplate`，且前端完全没有 `targets` 与
 * `parameters` 表单。草稿能存（config 是自由 map），一点发布就是
 * {@code NODE_CONFIG_INVALID}——用户在 UI 上永远建不出可发布的工作流。</p>
 *
 * <p>这里把"面板现在写什么"逐字固化：下列每个定义都必须 0 ERROR 通过发布校验。
 * 前端再改一次字段名，这个测试立刻红——比等用户在 UI 上撞 422 早得多。</p>
 *
 * <p><b>反例同样锁定</b>：把任一字段名换回旧名，必须报 {@code NODE_CONFIG_INVALID}，
 * 证明这条契约真的在执法，不是空跑。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("节点配置字段名契约 — 前端面板写出的键必须能发布（C4）")
class NodeConfigFieldNameContractTest {

    private static final Long OWNER = 42L;

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

    /**
     * 面板当前写出的定义（与 `NodeFormPanel.tsx` / `DEFAULT_NODE_CONFIG` 逐字段对齐）：
     * 五种节点类型各一个，覆盖 targets 四种 mode 与 parameters/rawParams。
     */
    private static final String UI_BUILT_DEFINITION = """
            {"schemaVersion":1,
             "nodes":[
               {"key":"scan","type":"script-exec","name":"扫描","position":{"x":0,"y":0},
                "config":{"script":"df -h {{MOUNT}}","parameters":{"MOUNT":"/"},"rawParams":[],
                          "targets":{"mode":"explicit","agentIds":["ag-1"]},
                          "riskPolicy":"block","onUnsafeValue":"reject"}},
               {"key":"pick","type":"script-exec","name":"按标签选","position":{"x":0,"y":0},
                "config":{"script":"hostname","parameters":{},"targets":{"mode":"tag","tagIds":[3,7]}}},
               {"key":"decide","type":"condition","name":"判断","position":{"x":0,"y":0},
                "config":{"expression":"nodes.scan.output.usage > 90"}},
               {"key":"ask","type":"approval","name":"人工确认","position":{"x":0,"y":0},
                "config":{"mode":"approval","title":"确认清理","approverRole":"ADMIN",
                          "timeoutSec":86400,"onTimeout":"reject","parameters":{}}},
               {"key":"tell","type":"notify","name":"通知","position":{"x":0,"y":0},
                "config":{"channels":["dingtalk","telegram"],"messageTemplate":"done: {{FINAL}}",
                          "parameters":{"FINAL":"${nodes.scan.output}"},"level":"info"}},
               {"key":"pause","type":"wait","name":"等一会","position":{"x":0,"y":0},
                "config":{"durationSec":60}}],
             "edges":[
               {"id":"e1","from":"scan","to":"pick"},
               {"id":"e2","from":"pick","to":"decide"},
               {"id":"e3","from":"decide","to":"ask","condition":"nodes.scan.output.usage > 90"},
               {"id":"e4","from":"ask","to":"tell"},
               {"id":"e5","from":"tell","to":"pause"}]}
            """;

    /** 旧契约：scriptContent / channel / message —— 必须被发布门禁拦下 */
    private static final String LEGACY_SCRIPT_CONTENT_DEFINITION = """
            {"schemaVersion":1,
             "nodes":[
               {"key":"scan","type":"script-exec","name":"扫描","position":{"x":0,"y":0},
                "config":{"scriptContent":"df -h"}}],
             "edges":[]}
            """;

    private static final String LEGACY_CHANNEL_MESSAGE_DEFINITION = """
            {"schemaVersion":1,
             "nodes":[
               {"key":"tell","type":"notify","name":"通知","position":{"x":0,"y":0},
                "config":{"channel":"webhook","message":"done"}}],
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
                        mock(TagService.class), mock(AgentRepository.class), mock(JobRepository.class),
                        mock(TaskRepository.class), approvalRepository, approvalStore,
                        expressionEvaluator, riskEngine, dagValidator, new SensitiveDataFilter(),
                        objectMapper, properties, transactionManager),
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

    @Test
    @DisplayName("面板写出的定义（五类节点 + targets 四种 mode + parameters）0 ERROR 通过发布校验")
    void uiBuiltDefinition_publishesWithZeroErrors() {
        Workflow workflow = newWorkflow();
        service.saveDraft(workflow.getId(), draft(UI_BUILT_DEFINITION, 0, null), OWNER, authOf(OWNER), null);

        WorkflowVersion published = service.publish(workflow.getId(), "built from the UI", OWNER,
                authOf(OWNER), null);

        assertThat(published.getIsValid()).isTrue();
    }

    @Test
    @DisplayName("validate 同一份定义也 0 ERROR（发布前的 UI 提示口径一致）")
    void uiBuiltDefinition_validatesClean() {
        var result = service.validate(json(UI_BUILT_DEFINITION));
        assertThat(result.getValid()).isTrue();
        assertThat(result.getIssues()).isEmpty();
    }

    @Test
    @DisplayName("targets.mode=cluster / variable 同样可发布（寻址表单的另外两种 mode）")
    void clusterAndVariableTargets_publish() {
        String definition = """
                {"schemaVersion":1,
                 "nodes":[
                   {"key":"a","type":"script-exec","name":"a","position":{"x":0,"y":0},
                    "config":{"script":"hostname","parameters":{},"targets":{"mode":"cluster","clusterIds":[1,2]}}},
                   {"key":"b","type":"script-exec","name":"b","position":{"x":0,"y":0},
                    "config":{"script":"hostname","parameters":{},"targets":{"mode":"variable","variableRef":"nodes.a.output"}}}],
                 "edges":[{"id":"e1","from":"a","to":"b"}]}
                """;
        Workflow workflow = newWorkflow();
        service.saveDraft(workflow.getId(), draft(definition, 0, null), OWNER, authOf(OWNER), null);

        assertThat(service.publish(workflow.getId(), null, OWNER, authOf(OWNER), null).getIsValid()).isTrue();
    }

    @Test
    @DisplayName("缺 targets.mode → NODE_CONFIG_INVALID（寻址不是可选项）")
    void missingTargetsMode_isRejected() {
        String definition = """
                {"schemaVersion":1,
                 "nodes":[
                   {"key":"a","type":"script-exec","name":"a","position":{"x":0,"y":0},
                    "config":{"script":"hostname","parameters":{}}}],
                 "edges":[]}
                """;
        var result = service.validate(json(definition));
        assertThat(result.getValid()).isFalse();
        assertThat(result.getIssues())
                .anyMatch(i -> "NODE_CONFIG_INVALID".equals(i.getCode()) && "ERROR".equals(i.getSeverity()));
    }

    @Test
    @DisplayName("旧字段名 scriptContent 仍然发布失败（契约在执法，不是空跑）")
    void legacyScriptContent_isStillRejected() {
        Workflow workflow = newWorkflow();
        service.saveDraft(workflow.getId(), draft(LEGACY_SCRIPT_CONTENT_DEFINITION, 0, null),
                OWNER, authOf(OWNER), null);

        assertThatThrownBy(() -> service.publish(workflow.getId(), null, OWNER, authOf(OWNER), null))
                .isInstanceOfSatisfying(WorkflowValidationFailedException.class, e ->
                        assertThat(e.getResult().getIssues())
                                .anyMatch(i -> "NODE_CONFIG_INVALID".equals(i.getCode())
                                        && i.getMessage().contains("script")));
    }

    @Test
    @DisplayName("旧字段名 channel / message 仍然发布失败（channels + messageTemplate 是硬必填）")
    void legacyChannelMessage_isStillRejected() {
        Workflow workflow = newWorkflow();
        service.saveDraft(workflow.getId(), draft(LEGACY_CHANNEL_MESSAGE_DEFINITION, 0, null),
                OWNER, authOf(OWNER), null);

        assertThatThrownBy(() -> service.publish(workflow.getId(), null, OWNER, authOf(OWNER), null))
                .isInstanceOfSatisfying(WorkflowValidationFailedException.class, e -> {
                    assertThat(e.getResult().getIssues())
                            .anyMatch(i -> "NODE_CONFIG_INVALID".equals(i.getCode())
                                    && i.getMessage().contains("channels"));
                    assertThat(e.getResult().getIssues())
                            .anyMatch(i -> "NODE_CONFIG_INVALID".equals(i.getCode())
                                    && i.getMessage().contains("messageTemplate"));
                });
    }

    // ---------- 辅助 ----------

    private Workflow newWorkflow() {
        WorkflowCreateRequest request = new WorkflowCreateRequest();
        request.setName("ui-built");
        request.setVisibility("private");
        return service.create(request, OWNER, authOf(OWNER), null);
    }

    private Authentication authOf(Long userId) {
        Authentication auth = mock(Authentication.class);
        lenient().when(auth.getPrincipal()).thenReturn(userId);
        lenient().when(auth.getName()).thenReturn("user-" + userId);
        return auth;
    }

    private WorkflowDraftSaveRequest draft(String definitionJson, int baseVersion, String onConflict) {
        WorkflowDraftSaveRequest request = new WorkflowDraftSaveRequest();
        request.setDefinition(json(definitionJson));
        request.setBaseVersion(baseVersion);
        request.setOnConflict(onConflict);
        request.setChangelog("edit");
        return request;
    }

    private JsonNode json(String definitionJson) {
        try {
            return objectMapper.readTree(definitionJson);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
