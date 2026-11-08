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
import com.easyshell.server.service.ClusterService;
import com.easyshell.server.service.TagService;
import com.easyshell.server.websocket.AgentWebSocketHandler;
import com.easyshell.server.websocket.TaskLogWebSocketHandler;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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
        lenient().when(agentWebSocketHandler.dispatchJob(any(), any(), any(), anyInt())).thenReturn(true);
    }

    private Agent onlineAgent(String id) {
        Agent agent = new Agent();
        agent.setId(id);
        agent.setHostname(id + "-host");
        agent.setIp("127.0.0.1");
        agent.setStatus(1);
        return agentRepository.save(agent);
    }

    private TaskCreateRequest request(String script, boolean riskCleared) {
        TaskCreateRequest request = new TaskCreateRequest();
        request.setName("gate-test");
        request.setScriptContent(script);
        request.setAgentIds(List.of("ag-gate"));
        request.setTimeoutSeconds(60);
        request.setRiskCleared(riskCleared);
        return request;
    }

    @Test
    @DisplayName("高危脚本在咽喉要道被拒：449，且不落 task/job 行")
    void highRiskScript_isBlockedAtChokepoint() {
        onlineAgent("ag-gate");

        assertThatThrownBy(() -> taskService.createAndDispatch(
                request("rm /tmp/old.log", false), 42L))
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
                request("rm -rf /", false), 42L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(449));

        assertThat(taskRepository.count()).isZero();
    }

    @Test
    @DisplayName("LOW 脚本照常放行派发（既有行为不回归）")
    void lowRiskScript_isDispatched() {
        onlineAgent("ag-gate");

        Task created = taskService.createAndDispatch(request("echo hi", false), 42L);

        assertThat(created.getId()).startsWith("task_");
        assertThat(taskRepository.findById(created.getId())).isPresent();
        assertThat(jobRepository.findByTaskId(created.getId())).hasSize(1);
    }

    @Test
    @DisplayName("日常只读命令不受影响：df -h / ls / cat 均为 LOW 放行")
    void everydayReadOnlyCommands_pass() {
        onlineAgent("ag-gate");
        for (String script : List.of("df -h", "ls -la /tmp", "cat /etc/hosts", "bash -c \"df -h && free -m\"")) {
            assertThat(riskEngine.assessScript(script).getOverallRisk())
                    .as(script)
                    .isEqualTo(RiskLevel.LOW);
            Task created = taskService.createAndDispatch(request(script, false), 42L);
            assertThat(created.getId()).startsWith("task_");
        }
    }

    @Test
    @DisplayName("上游已授权路径（riskCleared）放行非 LOW 脚本：审批通过/定时巡检不被误拦")
    void riskCleared_allowsApprovedAndScheduledScripts() {
        onlineAgent("ag-gate");

        Task approved = taskService.createAndDispatch(
                request("systemctl restart nginx", true), 42L);
        assertThat(taskRepository.findById(approved.getId())).isPresent();

        Task scheduled = taskService.createAndDispatch(
                request("journalctl -p err --since \"1 hour ago\"", true), 42L);
        assertThat(taskRepository.findById(scheduled.getId())).isPresent();
    }

    @Test
    @DisplayName("幂等：同一脚本两次过门禁结果一致，不会二次报错或行为变化")
    void gate_isIdempotent() {
        onlineAgent("ag-gate");

        Task first = taskService.createAndDispatch(request("echo stable", false), 42L);
        Task second = taskService.createAndDispatch(request("echo stable", false), 42L);
        assertThat(second.getId()).isNotEqualTo(first.getId());

        // 非 LOW 脚本连续两次都必须在同一位置被拒
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> taskService.createAndDispatch(
                    request("rm /tmp/old.log", false), 42L))
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
}
