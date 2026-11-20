package com.easyshell.server.ai.service;

import com.easyshell.server.ai.config.AgenticConfigService;
import com.easyshell.server.ai.risk.CommandRiskEngine;
import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.entity.Agent;
import com.easyshell.server.model.entity.Task;
import com.easyshell.server.repository.AgentRepository;
import com.easyshell.server.repository.JobRepository;
import com.easyshell.server.repository.TaskRepository;
import com.easyshell.server.repository.TaskTargetSnapshotRepository;
import com.easyshell.server.security.ScriptAccess;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.service.ClusterService;
import com.easyshell.server.service.DispatchEvidenceVerifier;
import com.easyshell.server.service.HostAclService;
import com.easyshell.server.service.HostIdentityService;
import com.easyshell.server.service.TagService;
import com.easyshell.server.service.TaskService;
import com.easyshell.server.service.impl.TaskCounterService;
import com.easyshell.server.service.impl.TaskServiceImpl;
import com.easyshell.server.websocket.AgentWebSocketHandler;
import com.easyshell.server.websocket.TaskLogWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:ai_approval_rollback;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL",
        "spring.jpa.show-sql=false"
})
@Import({AiExecutionService.class, TaskServiceImpl.class, TaskCounterService.class,
        CommandRiskEngine.class, AiExecutionServiceApprovalRollbackTest.MapperConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiExecutionServiceApprovalRollbackTest {

    @TestConfiguration
    static class MapperConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Autowired private AiExecutionService aiExecutionService;
    @Autowired private TaskService taskService;
    @Autowired private TaskRepository tasks;
    @Autowired private JobRepository jobs;
    @Autowired private AgentRepository agents;

    @MockitoBean private AgenticConfigService configService;
    @MockitoBean private AuditLogService audit;
    @MockitoBean private AgentWebSocketHandler agentWebSocket;
    @MockitoBean private TaskLogWebSocketHandler taskWebSocket;
    @MockitoBean private ClusterService clusterService;
    @MockitoBean private TagService tagService;
    @MockitoBean private HostAclService hostAcl;
    @MockitoBean private HostIdentityService hostIdentity;
    @MockitoBean private TaskTargetSnapshotRepository targetSnapshots;
    @MockitoBean private ScriptAccess scriptAccess;
    @MockitoBean private DispatchEvidenceVerifier evidenceVerifier;

    @Test
    void rejectedOrdinaryApprovalEvidenceRollsBackApprovalAndDoesNotDispatch() {
        assertThat(AopUtils.isAopProxy(aiExecutionService)).isTrue();
        assertThat(AopUtils.getTargetClass(taskService)).isEqualTo(TaskServiceImpl.class);

        Agent agent = new Agent();
        agent.setId("approval-rollback-agent");
        agent.setHostname("approval-rollback-agent");
        agent.setIp("127.0.0.1");
        agent.setStatus(1);
        agents.saveAndFlush(agent);

        Task pending = new Task();
        pending.setId("task_approval_rollback");
        pending.setName("pending approval");
        pending.setScriptContent("systemctl restart nginx");
        pending.setCreatedBy(42L);
        pending.setStatus(6);
        pending.setSource("ai_auto");
        pending.setApprovalStatus("pending");
        pending.setTargetAgentIds("[\"approval-rollback-agent\"]");
        tasks.saveAndFlush(pending);
        long taskCount = tasks.count();
        long jobCount = jobs.count();

        when(hostIdentity.lock(agent.getId()))
                .thenReturn(new HostIdentityService.Identity(agent.getId(), 1L, true));
        when(hostAcl.allowedForGeneration(42L, agent.getId(), 1L, HostAclService.Permission.EXECUTE))
                .thenReturn(true);
        when(configService.getStringSet(anyString(), eq(Collections.emptySet())))
                .thenReturn(Collections.emptySet());
        when(configService.getStringList(anyString(), eq(Collections.emptyList())))
                .thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> aiExecutionService.approveExecution(pending.getId(), 99L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(449))
                .hasMessageContaining("派发授权证据不成立");

        verify(hostIdentity).lock(agent.getId());
        verify(hostAcl).allowedForGeneration(42L, agent.getId(), 1L, HostAclService.Permission.EXECUTE);
        Task reloaded = tasks.findById(pending.getId()).orElseThrow();
        assertThat(reloaded.getApprovalStatus()).isEqualTo("pending");
        assertThat(reloaded.getSource()).isEqualTo("ai_auto");
        assertThat(tasks.count()).isEqualTo(taskCount);
        assertThat(jobs.count()).isEqualTo(jobCount);
        verifyNoInteractions(audit, agentWebSocket, taskWebSocket, targetSnapshots, evidenceVerifier);
    }
}
