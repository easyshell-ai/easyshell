package com.easyshell.server.service.impl;

import com.easyshell.server.ai.config.AgenticConfigService;
import com.easyshell.server.ai.risk.CommandRiskEngine;
import com.easyshell.server.ai.risk.RiskLevel;
import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.dto.TaskCreateRequest;
import com.easyshell.server.model.entity.Agent;
import com.easyshell.server.model.entity.Task;
import com.easyshell.server.repository.AgentRepository;
import com.easyshell.server.repository.JobRepository;
import com.easyshell.server.repository.TaskRepository;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.service.ClusterService;
import com.easyshell.server.service.DispatchAuthority;
import com.easyshell.server.service.DispatchEvidenceVerifier;
import com.easyshell.server.service.HostAclService;
import com.easyshell.server.service.TagService;
import com.easyshell.server.websocket.AgentWebSocketHandler;
import com.easyshell.server.websocket.TaskLogWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B1-b 回归：风险门禁必须位于 createAndDispatch 这个唯一咽喉要道上，
 * 任何调用方（AI 工具、定时巡检、未来的工作流）都无法绕过。
 * 使用真实 CommandRiskEngine（配置走 fallback 白名单）驱动判定。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = {"spring.jpa.show-sql=false", "logging.level.org.hibernate.SQL=warn"})
@Import({TaskServiceImpl.class, TaskCounterService.class, CommandRiskEngine.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("TaskServiceImpl — 咽喉要道的风险门禁（B1-b）")
class TaskServiceImplRiskGateTest {

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private AgentRepository agentRepository;

    @MockitoBean
    private TaskLogWebSocketHandler logWebSocketHandler;

    @MockitoBean
    private AgentWebSocketHandler agentWebSocketHandler;

    @MockitoBean
    private ClusterService clusterService;

    @MockitoBean
    private TagService tagService;

    @MockitoBean
    private AgenticConfigService configService;

    /** 门禁现在依赖审计（豁免留痕）；本测试只关心派发结果，审计本身另有断言 */
    @MockitoBean
    private AuditLogService auditLogService;

    @MockitoBean
    private HostAclService hostAclService;

    @MockitoBean private com.easyshell.server.service.HostIdentityService hostIdentityService;
    @MockitoBean private com.easyshell.server.repository.TaskTargetSnapshotRepository targetSnapshots;
    @MockitoBean private com.easyshell.server.security.ScriptAccess scriptAccess;

    @Test
    void scriptIdIsAuthorizedBeforeAmbiguityOrRiskEvaluation() {
        var request = new TaskCreateRequest();
        request.setScriptId(17L);
        request.setScriptContent("echo forged");
        when(scriptAccess.requireRead(42L, 17L)).thenThrow(new BusinessException(404, "Script not found"));
        assertThatThrownBy(() -> taskService.createAndDispatch(request, 42L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(404));
        org.mockito.Mockito.doReturn(new com.easyshell.server.model.entity.Script()).when(scriptAccess).requireRead(42L, 17L);
        assertThatThrownBy(() -> taskService.createAndDispatch(request, 42L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(400));
    }

    /**
     * 证据校验器 mock：只认 USER_SCHEDULED_TASK，其余授权值（如 BUILTIN_READONLY_SCRIPT）
     * 因"没有校验器"而 fail-closed —— 与生产里漏注册校验器的行为一致。
     */
    @MockitoBean
    private DispatchEvidenceVerifier evidenceVerifier;

    @Autowired
    private TaskServiceImpl taskService;

    private CommandRiskEngine riskEngine;

    @BeforeEach
    void setUp() {
        jobRepository.deleteAll();
        taskRepository.deleteAll();
        agentRepository.deleteAll();
        lenient().when(configService.getStringSet(anyString(), eq(Collections.emptySet())))
                .thenReturn(Collections.emptySet());
        lenient().when(configService.getStringList(anyString(), eq(Collections.emptyList())))
                .thenReturn(Collections.emptyList());
        riskEngine = new CommandRiskEngine(configService);
        lenient().when(agentWebSocketHandler.isAgentConnected(anyString())).thenReturn(true);
        lenient().when(hostAclService.allowed(any(), anyString(), eq(HostAclService.Permission.EXECUTE))).thenReturn(true);
        lenient().when(hostIdentityService.lock(anyString())).thenAnswer(inv ->
                new com.easyshell.server.service.HostIdentityService.Identity(inv.getArgument(0), 1L, true));
        lenient().when(hostAclService.allowedForGeneration(any(), anyString(), org.mockito.ArgumentMatchers.anyLong(), eq(HostAclService.Permission.EXECUTE))).thenReturn(true);
        lenient().when(agentWebSocketHandler.dispatchJob(any(), any(), any(), anyInt())).thenReturn(true);
    }

    @Test
    void mixedTargetsDenyBeforeAnyTaskOrJobIsWritten() {
        onlineAgent("ag-gate");
        TaskCreateRequest request = request("echo hi");
        request.setClusterIds(List.of(1L));
        request.setTagIds(List.of(2L));
        when(clusterService.getAgentIdsByClusterIds(any())).thenReturn(List.of("ag-gate", "cluster-host"));
        when(tagService.getAgentIdsByTagIds(any())).thenReturn(List.of("tag-host"));
        onlineAgent("cluster-host");
        onlineAgent("tag-host");
        lenient().when(hostAclService.allowedForGeneration(42L, "tag-host", 1L, HostAclService.Permission.EXECUTE)).thenReturn(false);
        assertThatThrownBy(() -> taskService.createAndDispatch(request, 42L)).isInstanceOf(BusinessException.class);
        assertThat(taskRepository.count()).isZero();
        assertThat(jobRepository.count()).isZero();
    }

    @Test
    void evidenceIsCheckedOnlyAfterEveryTargetGenerationIsLockedAndAuthorized() {
        onlineAgent("ag-gate");
        onlineAgent("z-host");
        TaskCreateRequest request = request("rm /tmp/old.log");
        request.setAgentIds(List.of("z-host", "ag-gate"));
        when(evidenceVerifier.supports()).thenReturn(DispatchAuthority.USER_SCHEDULED_TASK);
        when(evidenceVerifier.verify("7", "rm /tmp/old.log", 42L)).thenReturn(null);

        assertThatThrownBy(() -> taskService.createAndDispatch(request, 42L,
                DispatchAuthority.USER_SCHEDULED_TASK, "7"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(449));

        var order = org.mockito.Mockito.inOrder(hostIdentityService, hostAclService, evidenceVerifier);
        order.verify(hostIdentityService).lock("ag-gate");
        order.verify(hostAclService).allowedForGeneration(42L, "ag-gate", 1L, HostAclService.Permission.EXECUTE);
        order.verify(hostIdentityService).lock("z-host");
        order.verify(hostAclService).allowedForGeneration(42L, "z-host", 1L, HostAclService.Permission.EXECUTE);
        order.verify(evidenceVerifier).verify("7", "rm /tmp/old.log", 42L);
        assertThat(taskRepository.count()).isZero();
        assertThat(jobRepository.count()).isZero();
    }

    @Test
    void missingIdentityAndAclFailureDenyBeforeWrites() {
        onlineAgent("ag-gate");
        TaskCreateRequest request = request("echo hi");
        assertThatThrownBy(() -> taskService.createAndDispatch(request, null)).isInstanceOf(BusinessException.class);
        when(hostAclService.allowedForGeneration(42L, "ag-gate", 1L, HostAclService.Permission.EXECUTE))
                .thenThrow(new IllegalStateException("database unavailable"));
        assertThatThrownBy(() -> taskService.createAndDispatch(request, 42L)).isInstanceOf(IllegalStateException.class);
        assertThat(taskRepository.count()).isZero();
        assertThat(jobRepository.count()).isZero();
    }

    private Agent onlineAgent(String id) {
        Agent agent = new Agent();
        agent.setId(id);
        agent.setHostname(id + "-host");
        agent.setIp("127.0.0.1");
        agent.setStatus(1);
        return agentRepository.save(agent);
    }

    private TaskCreateRequest request(String script) {
        TaskCreateRequest request = new TaskCreateRequest();
        request.setName("gate-test");
        request.setScriptContent(script);
        request.setAgentIds(List.of("ag-gate"));
        request.setTimeoutSeconds(60);
        return request;
    }

    /** 造一行审批通过的任务，作为 HUMAN_APPROVED 的证据 */
    private Task approvedTask(String id, String script, Long createdBy) {
        Task task = new Task();
        task.setId(id);
        task.setName("approved");
        task.setScriptContent(script);
        task.setScriptType("shell");
        task.setTimeoutSeconds(60);
        task.setStatus(6);
        task.setCreatedBy(createdBy);
        task.setSource("ai_auto");
        task.setApprovalStatus("approved");
        return taskRepository.save(task);
    }

    private BusinessException catchDispatch(TaskCreateRequest request, Long userId,
                                            DispatchAuthority authority, String evidenceRef) {
        try {
            taskService.createAndDispatch(request, userId, authority, evidenceRef);
            throw new AssertionError("expected BusinessException(449) but dispatch succeeded");
        } catch (BusinessException e) {
            return e;
        }
    }

    @Test
    @DisplayName("高危脚本在咽喉要道被拒：449，且不落 task/job 行")
    void highRiskScript_isBlockedAtChokepoint() {
        onlineAgent("ag-gate");

        assertThatThrownBy(() -> taskService.createAndDispatch(
                request("rm /tmp/old.log"), 42L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("中风险");

        assertThat(taskRepository.count()).isZero();
        assertThat(jobRepository.count()).isZero();
    }

    @Test
    @DisplayName("封禁脚本同样在咽喉要道被拒（fail-closed）")
    void bannedScript_isBlockedAtChokepoint() {
        onlineAgent("ag-gate");

        assertThatThrownBy(() -> taskService.createAndDispatch(
                request("rm -rf /"), 42L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(449));

        assertThat(taskRepository.count()).isZero();
    }

    @Test
    @DisplayName("LOW 脚本照常放行派发（既有行为不回归）")
    void lowRiskScript_isDispatched() {
        onlineAgent("ag-gate");

        Task created = taskService.createAndDispatch(request("echo hi"), 42L);

        assertThat(created.getId()).startsWith("task_");
        assertThat(taskRepository.findById(created.getId())).isPresent();
        assertThat(jobRepository.findByTaskId(created.getId())).hasSize(1);
    }

    @Test
    @DisplayName("日常只读命令不受影响：df -h / ls / cat / find 只读 / bash -c 降级均为 LOW 放行")
    void everydayReadOnlyCommands_pass() {
        onlineAgent("ag-gate");
        for (String script : List.of("df -h", "ls -la /tmp", "cat /etc/hosts",
                "find /var/log -name '*.log' -mtime +7", "bash -c \"df -h && free -m\"")) {
            assertThat(riskEngine.assessScript(script).getOverallRisk())
                    .as(script)
                    .isEqualTo(RiskLevel.LOW);
            Task created = taskService.createAndDispatch(request(script), 42L);
            assertThat(created.getId()).startsWith("task_");
        }
    }

    @Test
    @DisplayName("幂等：同一脚本两次过门禁结果一致，不会二次报错或行为变化")
    void gate_isIdempotent() {
        onlineAgent("ag-gate");

        Task first = taskService.createAndDispatch(request("echo stable"), 42L);
        Task second = taskService.createAndDispatch(request("echo stable"), 42L);
        assertThat(second.getId()).isNotEqualTo(first.getId());

        // 非 LOW 脚本连续两次都必须在同一位置被拒
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> taskService.createAndDispatch(
                    request("rm /tmp/old.log"), 42L))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Test
    @DisplayName("参数替换后再过门禁：占位符替换出的高危载荷同样被拦")
    void substitutedPayload_isAssessedAfterSubstitution() {
        onlineAgent("ag-gate");
        TaskCreateRequest request = new TaskCreateRequest();
        request.setName("param-test");
        request.setScriptContent("echo {{MSG}}");
        request.setAgentIds(List.of("ag-gate"));
        request.setTimeoutSeconds(60);
        request.setParameters(java.util.Map.of("MSG", "; rm /tmp/old.log"));

        assertThatThrownBy(() -> taskService.createAndDispatch(request, 42L))
                .isInstanceOf(BusinessException.class);
        assertThat(taskRepository.count()).isZero();
    }

    @Test
    @DisplayName("LOW 脚本的参数替换后仍为 LOW：正常派发（既有语义不回退）")
    void lowRiskWithParameters_isDispatched() {
        onlineAgent("ag-gate");
        TaskCreateRequest request = new TaskCreateRequest();
        request.setName("param-low");
        request.setScriptContent("echo {{MSG}}");
        request.setAgentIds(List.of("ag-gate"));
        request.setTimeoutSeconds(60);
        request.setParameters(java.util.Map.of("MSG", "hello"));

        Task created = taskService.createAndDispatch(request, 42L);
        assertThat(created.getScriptContent()).isEqualTo("echo hello");
    }

    // ------------------------------------------------------------------
    // P0-2：riskCleared 布尔旁路收窄为 DispatchAuthority 枚举 + 证据校验
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Jackson 绑定攻击：请求体里的 riskCleared 不再被绑定，非 LOW 脚本仍被门禁拦下")
    void riskClearedInBody_cannotBypassGate() throws Exception {
        onlineAgent("ag-gate");

        // 复核官用的攻击手法：向 POST /api/v1/task 的 body 里塞 "riskCleared": true。
        // Spring Boot 默认关闭 FAIL_ON_UNKNOWN_PROPERTIES（未知字段被忽略而不是报错），
        // 字段已从 DTO 移除后这行 JSON 什么也改变不了。
        ObjectMapper mapper = new ObjectMapper()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        String attackBody = """
                {"name":"attack","scriptContent":"systemctl restart nginx",
                 "agentIds":["ag-gate"],"timeoutSeconds":60,"riskCleared":true}""";
        TaskCreateRequest request = mapper.readValue(attackBody, TaskCreateRequest.class);

        BusinessException ex = catchDispatch(request, 42L, null, null);
        assertThat(ex.getCode()).isEqualTo(449);
        assertThat(taskRepository.count()).isZero();

        // DTO 上不再有该字段（编译期 + 反射双重锁定）
        assertThatThrownBy(() -> TaskCreateRequest.class.getDeclaredField("riskCleared"))
                .isInstanceOf(NoSuchFieldException.class);
    }

    @Test
    @DisplayName("HUMAN_APPROVED：旧审批任务未绑定派发目标 → 449")
    void humanApproved_withApprovedTask_failClosed() {
        onlineAgent("ag-gate");
        String script = "systemctl restart nginx";
        Task approved = approvedTask("task_appr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8),
                script, 42L);

        BusinessException ex = catchDispatch(request(script), 42L,
                DispatchAuthority.HUMAN_APPROVED, approved.getId());

        assertThat(ex.getCode()).isEqualTo(449);
        assertThat(taskRepository.count()).isEqualTo(1);
        verify(auditLogService, never()).logSync(eq(42L), any(), eq("SCRIPT_DISPATCH"), any(), any(),
                any(), any(), eq("success"));
    }

    @Test
    @DisplayName("HUMAN_APPROVED：没有审批通过的任务行 → 449（fail-closed）")
    void humanApproved_withoutApprovedRow_failClosed() {
        onlineAgent("ag-gate");

        BusinessException ex = catchDispatch(request("systemctl restart nginx"), 42L,
                DispatchAuthority.HUMAN_APPROVED, "task_does_not_exist");
        assertThat(ex.getCode()).isEqualTo(449);
        assertThat(taskRepository.count()).isZero();
    }

    @Test
    @DisplayName("HUMAN_APPROVED：换脚本蹭授权 → 449（证据与脚本不一致）")
    void humanApproved_swappedScript_failClosed() {
        onlineAgent("ag-gate");
        Task approved = approvedTask("task_appr_swap", "echo hi", 42L);

        BusinessException ex = catchDispatch(request("systemctl restart nginx"), 42L,
                DispatchAuthority.HUMAN_APPROVED, approved.getId());
        assertThat(ex.getCode()).isEqualTo(449);
    }

    @Test
    @DisplayName("HUMAN_APPROVED：旧审批任务不能豁免 BANNED")
    void humanApproved_bannedScript_failClosed() {
        onlineAgent("ag-gate");
        Task approved = approvedTask("task_appr_banned", "rm -rf /", 42L);

        BusinessException ex = catchDispatch(request("rm -rf /"), 42L,
                DispatchAuthority.HUMAN_APPROVED, approved.getId());

        assertThat(ex.getCode()).isEqualTo(449);
        assertThat(taskRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("HUMAN_APPROVED：LOW 也不能使用未绑定的旧审批证据")
    void humanApproved_lowScriptWithLegacyEvidence_failClosed() {
        onlineAgent("ag-gate");
        Task approved = approvedTask("task_appr_low", "echo hi", 42L);

        BusinessException ex = catchDispatch(request("echo hi"), 42L,
                DispatchAuthority.HUMAN_APPROVED, approved.getId());

        assertThat(ex.getCode()).isEqualTo(449);
        assertThat(taskRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("USER_SCHEDULED_TASK：证据成立 → 放行非 LOW 巡检脚本，并写豁免审计")
    void userScheduledTask_withValidEvidence_passes() {
        onlineAgent("ag-gate");
        when(evidenceVerifier.supports()).thenReturn(DispatchAuthority.USER_SCHEDULED_TASK);
        when(evidenceVerifier.verify(eq("7"), anyString(), eq(42L))).thenReturn("ai_scheduled_task:7");

        Task created = taskService.createAndDispatch(request("journalctl -p err"), 42L,
                DispatchAuthority.USER_SCHEDULED_TASK, "7");

        assertThat(created.getId()).startsWith("task_");
        verify(auditLogService).logSync(eq(42L), any(), eq("SCRIPT_DISPATCH"), eq("task"), isNull(),
                contains("authority=USER_SCHEDULED_TASK"), isNull(), eq("success"));
    }

    @Test
    @DisplayName("USER_SCHEDULED_TASK：证据不成立（换任务/换用户）→ 449")
    void userScheduledTask_invalidEvidence_failClosed() {
        onlineAgent("ag-gate");
        when(evidenceVerifier.supports()).thenReturn(DispatchAuthority.USER_SCHEDULED_TASK);
        when(evidenceVerifier.verify(any(), anyString(), any())).thenReturn(null);

        BusinessException ex = catchDispatch(request("journalctl -p err"), 42L,
                DispatchAuthority.USER_SCHEDULED_TASK, "7");
        assertThat(ex.getCode()).isEqualTo(449);
        assertThat(taskRepository.count()).isZero();
    }

    @Test
    @DisplayName("USER_SCHEDULED_TASK + BANNED 脚本：证据再成立也不可豁免 → 449")
    void userScheduledTask_bannedScript_notExemptable() {
        onlineAgent("ag-gate");
        when(evidenceVerifier.supports()).thenReturn(DispatchAuthority.USER_SCHEDULED_TASK);
        when(evidenceVerifier.verify(eq("7"), anyString(), eq(42L))).thenReturn("ai_scheduled_task:7");

        BusinessException ex = catchDispatch(request("rm -rf /"), 42L,
                DispatchAuthority.USER_SCHEDULED_TASK, "7");
        assertThat(ex.getCode()).isEqualTo(449);
        assertThat(taskRepository.count()).isZero();
    }

    @Test
    @DisplayName("未注册证据校验器的授权值 → 449（fail-closed，如 BUILTIN_READONLY_SCRIPT）")
    void authorityWithoutVerifier_failClosed() {
        onlineAgent("ag-gate");

        BusinessException ex = catchDispatch(request("systemctl restart nginx"), 42L,
                DispatchAuthority.BUILTIN_READONLY_SCRIPT, "builtin:detection");
        assertThat(ex.getCode()).isEqualTo(449);
        assertThat(taskRepository.count()).isZero();
    }
}
