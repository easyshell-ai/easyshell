package com.easyshell.server.controller;

import com.easyshell.server.ai.config.AgenticConfigService;
import com.easyshell.server.ai.model.vo.CommandRisk;
import com.easyshell.server.ai.model.vo.RiskAssessment;
import com.easyshell.server.ai.risk.CommandRiskEngine;
import com.easyshell.server.ai.risk.RiskLevel;
import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.common.exception.GlobalExceptionHandler;
import com.easyshell.server.model.dto.TaskCreateRequest;
import com.easyshell.server.model.entity.Script;
import com.easyshell.server.model.entity.Task;
import com.easyshell.server.model.entity.Job;
import com.easyshell.server.model.entity.WorkflowNodeRun;
import com.easyshell.server.model.vo.TaskDetailVO;
import com.easyshell.server.repository.ScriptRepository;
import com.easyshell.server.repository.TaskRepository;
import com.easyshell.server.repository.WorkflowNodeRunRepository;
import com.easyshell.server.service.HostAclService;
import com.easyshell.server.workflow.controller.WorkflowAccess;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.service.ClusterService;
import com.easyshell.server.service.TagService;
import com.easyshell.server.service.TaskService;
import com.easyshell.server.common.result.R;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B2/B4 回归：task 详情/列表端点的资源归属校验。
 * detail 端点此前已有 requireAccess（此处锁定不回退）；
 * list/page 端点此前不过滤 createdBy，任何登录用户可读全部任务（存量越权读）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TaskController — 任务读写端点的归属校验")
class TaskControllerTest {

    private static final String SECRET = "PRIVATE_COMMAND_SENTINEL_8675309";

    @Mock
    private TaskService taskService;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private CommandRiskEngine riskEngine;
    @Mock
    private com.easyshell.server.security.ScriptAccess scriptAccess;
    @Mock
    private TaskRepository taskRepository;
    @Mock
    private ClusterService clusterService;
    @Mock
    private TagService tagService;
    @Mock private WorkflowNodeRunRepository nodeRunRepository;
    @Mock private WorkflowAccess workflowAccess;
    @Mock private HostAclService hostAclService;
    @Mock private com.easyshell.server.service.HostIdentityService hostIdentityService;
    @Mock private com.easyshell.server.repository.AgentRepository agentRepository;
    @Mock private com.easyshell.server.repository.TaskTargetSnapshotRepository targetSnapshots;
    @Mock private com.easyshell.server.repository.TaskAccessQueryRepository taskAccessQueries;

    private TaskController controller;

    private Authentication auth(Long userId, String role) {
        Authentication auth = mock(Authentication.class);
        // lenient：admin 路径不读 principal；create 路径不读 authorities
        lenient().when(auth.getPrincipal()).thenReturn(userId);
        lenient().when(auth.isAuthenticated()).thenReturn(true);
        java.util.Collection<org.springframework.security.core.GrantedAuthority> authorities =
                List.of(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()));
        // thenAnswer 避开 getAuthorities() 通配符捕获导致的 thenReturn 类型约束
        lenient().when(auth.getAuthorities()).thenAnswer(inv -> authorities);
        return auth;
    }

    @BeforeEach
    void setUp() {
        controller = new TaskController(taskService, auditLogService, riskEngine,
                scriptAccess, taskRepository, clusterService, tagService, new ObjectMapper(),
                nodeRunRepository, workflowAccess, hostAclService, hostIdentityService, agentRepository, targetSnapshots,
                taskAccessQueries);
        lenient().when(taskAccessQueries.currentRole(42L)).thenReturn("operator");
    }

    private Task taskOwnedBy(Long userId) {
        Task task = new Task();
        task.setId("task_x");
        task.setName("job");
        task.setCreatedBy(userId);
        return task;
    }

    @Test
    void collectionReadsDelegateToDatabaseAuthorization() {
        Authentication actor = auth(42L, "admin");
        controller.list(actor);
        controller.page(1, 2, 10, actor);
        controller.agentJobs("agent-1", actor);
        verify(taskAccessQueries).list(42L);
        verify(taskAccessQueries).page(42L, 1, 2, 10);
        verify(taskAccessQueries).agentJobs(42L, "agent-1");
    }

    @Test
    void detailDoesNotTrustDemotedAdminToken() {
        when(taskService.getTaskDetail("task_x"))
                .thenReturn(TaskDetailVO.builder().task(taskOwnedBy(99L)).jobs(List.of()).build());
        assertThatThrownBy(() -> controller.detail("task_x", auth(42L, "admin")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(403));
    }

    @Test
    @DisplayName("detail：非属主非管理员 → 403")
    void detail_nonOwner_isDenied() {
        when(taskService.getTaskDetail("task_x"))
                .thenReturn(TaskDetailVO.builder().task(taskOwnedBy(99L)).jobs(List.of()).build());

        assertThatThrownBy(() -> controller.detail("task_x", auth(42L, "operator")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(403));
    }

    @ParameterizedTest
    @ValueSource(strings = {"operator", "admin"})
    void reads_reportIdentityUnavailable(String role) throws Exception {
        var auth = auth(42L, role);
        Task task = taskOwnedBy(42L);
        task.setSource("workflow");
        Job job = new Job();
        job.setAgentId("agent-1");
        when(taskService.getTaskDetail("task_x"))
                .thenReturn(TaskDetailVO.builder().task(task).jobs(List.of(job)).build());
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        for (String path : List.of("/api/v1/task/task_x")) {
            var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                            .principal(auth))
                    .andExpect(status().isServiceUnavailable()).andReturn().getResponse();
            JsonNode body = new ObjectMapper().readTree(response.getContentAsString());
            assertThat(body.path("code").asInt()).isEqualTo(503);
            assertThat(body.path("message").asText()).startsWith("TASK_RESOURCE_IDENTITY_UNAVAILABLE:");
            assertThat(body.path("data").isNull()).isTrue();
            assertThat(response.getContentAsString()).doesNotContain("agent-1", "workflowSource", "totalElements");
        }
        verify(hostAclService, never()).allowed(any(), anyString(), any());
        verify(nodeRunRepository, never()).findByTaskId(anyString());
        verify(taskService, never()).listTasks();
        verify(taskService, never()).getAgentJobs(anyString());
    }

    @Test
    void detail_emptyJobsDoesNotProveNoTargets() {
        Task task = taskOwnedBy(42L);
        task.setTargetAgentIds("[\"agent-1\"]");
        when(taskService.getTaskDetail("task_x"))
                .thenReturn(TaskDetailVO.builder().task(task).jobs(List.of()).build());
        assertThatThrownBy(() -> controller.detail("task_x", auth(42L, "operator")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(503));
    }

    @Test
    void detail_completeSnapshotRequiresViewForEveryGeneration() {
        Task task = taskOwnedBy(42L);
        task.setTotalCount(2);
        when(taskService.getTaskDetail("task_x"))
                .thenReturn(TaskDetailVO.builder().task(task).jobs(List.of()).build());
        when(targetSnapshots.findComplete("task_x")).thenReturn(java.util.Optional.of(
                new com.easyshell.server.model.dto.TaskTargetSnapshot("task_x", List.of(
                        new com.easyshell.server.model.dto.TaskTargetSnapshot.Target("a", 1),
                        new com.easyshell.server.model.dto.TaskTargetSnapshot.Target("b", 2)))));
        when(hostAclService.allowedForGeneration(42L, "a", 1, HostAclService.Permission.VIEW)).thenReturn(true);
        assertThatThrownBy(() -> controller.detail("task_x", auth(42L, "operator")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(403));
        verify(nodeRunRepository, never()).findByTaskId(anyString());
    }

    @Test
    @DisplayName("create：非 LOW 脚本返回 449 且不派发（Controller 层门禁不回退）")
    void create_nonLowScript_returns449WithoutDispatch() {
        when(riskEngine.assessScript("rm -rf /tmp/x"))
                .thenReturn(RiskAssessment.of(RiskLevel.MEDIUM, List.of()));
        TaskCreateRequest request = new TaskCreateRequest();
        request.setName("risky");
        request.setScriptContent("rm -rf /tmp/x");
        request.setAgentIds(List.of("ag_1"));

        R<?> result = controller.create(request, auth(42L, "operator"), new MockHttpServletRequest());

        assertThat(result.getCode()).isEqualTo(449);
        verify(taskService, never()).createAndDispatch(any(), any());
    }

    private static Stream<Arguments> riskyScripts() {
        return Stream.of(
                Arguments.of(RiskLevel.MEDIUM, "rm -f /tmp/" + SECRET,
                        "包含 1 条中风险命令，需人工确认"),
                Arguments.of(RiskLevel.MEDIUM, "echo hi\nrm -f /tmp/" + SECRET + "\nkill 1234",
                        "包含 2 条中风险命令，需人工确认"),
                Arguments.of(RiskLevel.HIGH, "shutdown -h now # " + SECRET,
                        "包含高危命令，需管理员审批后执行"),
                Arguments.of(RiskLevel.BANNED, "rm -rf /tmp/" + SECRET,
                        "包含封禁命令，需管理员审批后执行")
        );
    }

    private String postCreate(TaskCreateRequest request) throws Exception {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build()
                .perform(post("/api/v1/task")
                        .principal(auth(42L, "operator"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private TaskCreateRequest requestWithScript(String script) {
        TaskCreateRequest request = new TaskCreateRequest();
        request.setName("risky");
        request.setScriptContent(script);
        request.setAgentIds(List.of("ag_1"));
        return request;
    }

    @ParameterizedTest
    @MethodSource("riskyScripts")
    @DisplayName("create：真实风险引擎各非 LOW 等级的完整 449 JSON 不回显命令")
    void create_realRiskAssessment_doesNotExposeCommand(
            RiskLevel level, String script, String expectedExplanation) throws Exception {
        AgenticConfigService config = mock(AgenticConfigService.class);
        when(config.getStringSet(anyString(), eq(Collections.emptySet()))).thenReturn(Collections.emptySet());
        RiskAssessment assessment = new CommandRiskEngine(config).assessScript(script);
        assertThat(assessment.getOverallRisk()).isEqualTo(level);
        if (level != RiskLevel.BANNED) {
            assertThat(assessment.getCommandRisks()).extracting(CommandRisk::getCommand)
                    .anySatisfy(command -> assertThat(command).contains(SECRET));
        }
        when(riskEngine.assessScript(script)).thenReturn(assessment);

        String json = postCreate(requestWithScript(script));
        JsonNode body = new ObjectMapper().readTree(json);

        assertThat(body.path("code").asInt()).isEqualTo(449);
        assertThat(body.path("message").asText()).isEqualTo(expectedExplanation);
        assertThat(body.path("data").path("overallRisk").asText()).isEqualTo(level.name());
        assertThat(body.path("data").path("explanation").asText()).isEqualTo(expectedExplanation);
        assertThat(body.path("data").path("bannedMatches").isArray()).isTrue();
        assertThat(body.path("data").path("bannedMatches").size()).isZero();
        assertThat(body.path("data").size()).isEqualTo(3);
        assertThat(body.path("data").has("commandRisks")).isFalse();
        assertThat(json).doesNotContain(script, SECRET);
        verify(taskService, never()).createAndDispatch(any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = RiskLevel.class, names = {"MEDIUM", "HIGH", "BANNED"})
    @DisplayName("create：不得信任风险说明、原因或自定义匹配中的任意文本")
    void create_untrustedAssessmentFields_areNotSerialized(RiskLevel level) throws Exception {
        String script = "run " + SECRET;
        RiskAssessment assessment = RiskAssessment.builder()
                .overallRisk(level)
                .commandRisks(List.of(CommandRisk.builder().command(script).level(level)
                        .reason("REASON_SECRET_123").build()))
                .bannedMatches(List.of("MATCH_SECRET_123", script))
                .explanation("EXPLANATION_SECRET_123 " + script)
                .build();
        when(riskEngine.assessScript(script)).thenReturn(assessment);

        String json = postCreate(requestWithScript(script));
        JsonNode body = new ObjectMapper().readTree(json);

        assertThat(body.path("code").asInt()).isEqualTo(449);
        assertThat(body.path("data").path("overallRisk").asText()).isEqualTo(level.name());
        assertThat(body.path("data").path("bannedMatches").isArray()).isTrue();
        assertThat(body.path("data").path("bannedMatches").size()).isZero();
        assertThat(json).doesNotContain(script, SECRET, "REASON_SECRET_123",
                "MATCH_SECRET_123", "EXPLANATION_SECRET_123");
        verify(taskService, never()).createAndDispatch(any(), any());
    }

    @Test
    @DisplayName("create：自定义封禁规则可等于命令或含敏感参数，不得回显")
    void create_customBannedMatches_areNotExposed() throws Exception {
        String script = "deploy --token=" + SECRET;
        AgenticConfigService config = mock(AgenticConfigService.class);
        when(config.getStringSet("ai.risk.banned-commands", Collections.emptySet()))
                .thenReturn(Set.of(script, SECRET));
        RiskAssessment assessment = new CommandRiskEngine(config).assessScript(script);
        assertThat(assessment.getOverallRisk()).isEqualTo(RiskLevel.BANNED);
        assertThat(assessment.getBannedMatches()).contains(script, SECRET);
        assertThat(assessment.getExplanation()).contains(script, SECRET);
        when(riskEngine.assessScript(script)).thenReturn(assessment);

        String json = postCreate(requestWithScript(script));
        JsonNode body = new ObjectMapper().readTree(json);

        assertThat(body.path("code").asInt()).isEqualTo(449);
        assertThat(body.path("data").path("overallRisk").asText()).isEqualTo("BANNED");
        assertThat(body.path("data").path("bannedMatches").size()).isZero();
        assertThat(json).doesNotContain(script, SECRET);
        verify(taskService, never()).createAndDispatch(any(), any());
    }

    @Test
    @DisplayName("create：内置模式等于整条命令时也不得回显")
    void create_builtinBannedPatternEqualToCommand_isNotExposed() throws Exception {
        String script = "rm -rf /";
        AgenticConfigService config = mock(AgenticConfigService.class);
        when(config.getStringSet("ai.risk.banned-commands", Collections.emptySet()))
                .thenReturn(Collections.emptySet());
        RiskAssessment assessment = new CommandRiskEngine(config).assessScript(script);
        assertThat(assessment.getBannedMatches()).contains(script);
        when(riskEngine.assessScript(script)).thenReturn(assessment);

        String json = postCreate(requestWithScript(script));

        assertThat(new ObjectMapper().readTree(json).path("code").asInt()).isEqualTo(449);
        assertThat(json).doesNotContain(script);
        verify(taskService, never()).createAndDispatch(any(), any());
    }

    @Test
    @DisplayName("create：库脚本的参数替换后仍不得泄露")
    void create_savedScriptWithParameters_doesNotExposeResolvedCommand() throws Exception {
        String resolved = "shutdown -h now # " + SECRET;
        Script script = new Script();
        script.setContent("shutdown -h now # {{TOKEN}}");
        when(scriptAccess.requireRead(42L, 101L)).thenReturn(script);
        when(riskEngine.assessScript(resolved)).thenReturn(RiskAssessment.of(RiskLevel.HIGH,
                List.of(CommandRisk.builder().command(resolved).level(RiskLevel.HIGH).build())));
        TaskCreateRequest request = requestWithScript(null);
        request.setScriptId(101L);
        request.setParameters(Map.of("TOKEN", SECRET));

        String json = postCreate(request);

        assertThat(new ObjectMapper().readTree(json).path("code").asInt()).isEqualTo(449);
        assertThat(json).doesNotContain(resolved, SECRET);
        verify(riskEngine).assessScript(resolved);
        verify(taskService, never()).createAndDispatch(any(), any());
    }

    @Test
    void libraryAccessPrecedesRiskAssessmentAndAmbiguousInputIsRejected() {
        TaskCreateRequest request = requestWithScript("echo forged");
        request.setScriptId(101L);
        when(scriptAccess.requireRead(42L, 101L)).thenReturn(new Script());
        assertThatThrownBy(() -> controller.create(request, auth(42L, "operator"), mock(jakarta.servlet.http.HttpServletRequest.class)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(400));
        verify(riskEngine, never()).assessScript(any());
        verify(taskService, never()).createAndDispatch(any(), any());
    }

    @Test
    void approvalDoesNotAssessOrSaveInaccessibleLibraryScript() {
        TaskCreateRequest request = requestWithScript(null);
        request.setScriptId(101L);
        request.setParameters(Map.of("TOKEN", "secret"));
        when(scriptAccess.requireRead(42L, 101L)).thenThrow(new BusinessException(404, "Script not found"));
        assertThatThrownBy(() -> controller.submitForApproval(request, auth(42L, "operator"), mock(jakarta.servlet.http.HttpServletRequest.class)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(404));
        verify(riskEngine, never()).assessScript(any());
        verify(taskRepository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"assessment", "dispatch"})
    @DisplayName("create：下游 449 异常消息不得回显命令")
    void create_downstream449Exception_doesNotExposeMessage(String source) throws Exception {
        String script = "echo " + SECRET;
        BusinessException failure = new BusinessException(449, "封禁规则: " + script);
        if (source.equals("assessment")) {
            when(riskEngine.assessScript(script)).thenThrow(failure);
        } else {
            when(riskEngine.assessScript(script)).thenReturn(RiskAssessment.of(RiskLevel.LOW, List.of()));
            when(taskService.createAndDispatch(any(), eq(42L))).thenThrow(failure);
        }

        String json = postCreate(requestWithScript(script));

        assertThat(new ObjectMapper().readTree(json).path("code").asInt()).isEqualTo(449);
        assertThat(json).doesNotContain(script, SECRET);
    }

    @Test
    @DisplayName("create：非 449 业务异常保持原有行为")
    void create_otherBusinessException_isNotChanged() {
        String script = "echo hi";
        when(riskEngine.assessScript(script)).thenReturn(RiskAssessment.of(RiskLevel.LOW, List.of()));
        BusinessException failure = new BusinessException(400, "Invalid task");
        when(taskService.createAndDispatch(any(), eq(42L))).thenThrow(failure);

        assertThatThrownBy(() -> controller.create(requestWithScript(script), auth(42L, "operator"),
                new MockHttpServletRequest())).isSameAs(failure);
    }

    @Test
    @DisplayName("create：风险等级缺失时仍以安全说明拒绝")
    void create_missingRiskLevel_stillReturnsSafe449() throws Exception {
        String script = "run " + SECRET;
        when(riskEngine.assessScript(script)).thenReturn(RiskAssessment.builder()
                .explanation(script).bannedMatches(List.of(script)).build());

        String json = postCreate(requestWithScript(script));
        JsonNode body = new ObjectMapper().readTree(json);

        assertThat(body.path("code").asInt()).isEqualTo(449);
        assertThat(body.path("data").path("overallRisk").isNull()).isTrue();
        assertThat(body.path("data").path("explanation").asText()).isEqualTo("需人工确认");
        assertThat(json).doesNotContain(script, SECRET);
        verify(taskService, never()).createAndDispatch(any(), any());
    }

    @Test
    @DisplayName("create：LOW 脚本正常派发，createdBy 是真实用户")
    void create_lowScript_isDispatchedWithRealUser() {
        when(riskEngine.assessScript("echo hi")).thenReturn(RiskAssessment.of(RiskLevel.LOW, List.of()));
        when(taskService.createAndDispatch(any(), eq(42L))).thenReturn(taskOwnedBy(42L));
        TaskCreateRequest request = new TaskCreateRequest();
        request.setName("safe");
        request.setScriptContent("echo hi");
        request.setAgentIds(List.of("ag_1"));

        R<?> result = controller.create(request, auth(42L, "operator"), new MockHttpServletRequest());

        assertThat(result.getCode()).isEqualTo(200);
        verify(taskService).createAndDispatch(any(), eq(42L));
    }

}
