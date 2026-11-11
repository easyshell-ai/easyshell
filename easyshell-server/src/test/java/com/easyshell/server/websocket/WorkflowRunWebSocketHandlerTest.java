package com.easyshell.server.websocket;

import com.easyshell.server.config.WebSocketAuthInterceptor;
import com.easyshell.server.ai.security.SensitiveDataFilter;
import com.easyshell.server.model.entity.Workflow;
import com.easyshell.server.model.entity.WorkflowRun;
import com.easyshell.server.model.entity.User;
import com.easyshell.server.repository.UserRepository;
import com.easyshell.server.repository.WorkflowRepository;
import com.easyshell.server.repository.WorkflowRunRepository;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.workflow.engine.WorkflowEvent;
import com.easyshell.server.workflow.engine.WorkflowEventBroadcaster;
import com.easyshell.server.workflow.engine.NodeContext;
import com.easyshell.server.workflow.model.WorkflowNode;
import com.easyshell.server.workflow.service.WorkflowRunProvenanceService;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B4 / C-12：{@code /ws/workflow/run/{runId}} 的资源级授权回归。
 *
 * <p>与 1.1.2 修好的 {@code TaskLogWebSocketHandlerTest} 同一组判据：越权订阅被
 * POLICY_VIOLATION 关闭且不注册、属主放行、admin 放行、run 不存在拒、查询异常拒。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowRunWebSocketHandler — 订阅工作流运行的资源级授权")
class WorkflowRunWebSocketHandlerTest {

    private static final String RUN_ID = "wfr_owned_by_42";
    private static final String PATH = "/ws/workflow/run/" + RUN_ID + "?token=fake";
    private static final Long WORKFLOW_ID = 1L;

    @Mock
    private WorkflowRunRepository runRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private WorkflowRepository workflowRepository;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private WorkflowRunProvenanceService provenanceService;

    private WorkflowRunWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        handler = new WorkflowRunWebSocketHandler(runRepository, workflowRepository, auditLogService, userRepository);
        ReflectionTestUtils.setField(handler, "provenanceService", provenanceService);
        lenient().when(provenanceService.canView(any(), any())).thenReturn(true);
    }

    private WorkflowRun runStartedBy(Long userId) {
        WorkflowRun run = new WorkflowRun();
        run.setId(RUN_ID);
        run.setWorkflowId(WORKFLOW_ID);
        run.setStatus("running");
        run.setStartedBy(userId);
        return run;
    }

    private Workflow workflowOwnedBy(Long userId) {
        Workflow workflow = new Workflow();
        workflow.setId(WORKFLOW_ID);
        workflow.setName("cleanup");
        workflow.setCreatedBy(userId);
        return workflow;
    }

    private WebSocketSession session(Long userId, String role) throws Exception {
        if (userId != null) {
            lenient().when(userRepository.findById(userId)).thenReturn(Optional.of(user(role, 1)));
        }
        WebSocketSession session = org.mockito.Mockito.mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        if (userId != null) {
            attributes.put(WebSocketAuthInterceptor.ATTR_USER_ID, userId);
        }
        if (role != null) {
            attributes.put(WebSocketAuthInterceptor.ATTR_ROLE, role);
        }
        lenient().when(session.getAttributes()).thenReturn(attributes);
        lenient().when(session.getUri()).thenReturn(new URI(PATH));
        lenient().when(session.isOpen()).thenReturn(true);
        return session;
    }

    private User user(String role, int status) {
        User user = new User();
        user.setRole(role);
        user.setStatus(status);
        return user;
    }

    @Test
    void broadcasterMasksEventsAndNodeEmissionsAtRealWebSocketBoundary() throws Exception {
        WorkflowRun run = runStartedBy(42L);
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run));
        WebSocketSession session = session(42L, "operator");
        handler.afterConnectionEstablished(session);
        WorkflowEventBroadcaster broadcaster = new WorkflowEventBroadcaster(handler, new SensitiveDataFilter());
        broadcaster.accept(new WorkflowEvent("RUN_FAILED", RUN_ID, null, "password=secretvalue",
                Map.of("secret", "payloadvalue"), Instant.now()));
        WorkflowNode node = new WorkflowNode();
        node.setKey("node1");
        NodeContext context = new NodeContext(run, null, node, null, null, null, null, null,
                false, broadcaster, null);
        context.emit("NODE_STARTED", "token=nodevalue");

        org.mockito.ArgumentCaptor<TextMessage> messages = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(session, org.mockito.Mockito.times(2)).sendMessage(messages.capture());
        assertThat(messages.getAllValues().get(0).getPayload())
                .contains("password=***", "RUN_FAILED").doesNotContain("secretvalue", "payloadvalue");
        assertThat(messages.getAllValues().get(1).getPayload())
                .contains("token=***", "node1").doesNotContain("nodevalue");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"unknown", "Viewer", "ADMIN", "super_Admin", " operator "})
    void invalidDatabaseRoleRejectsOwnerOnConnect(String role) throws Exception {
        WebSocketSession session = session(42L, "operator");
        when(userRepository.findById(42L)).thenReturn(Optional.of(user(role, 1)));

        handler.afterConnectionEstablished(session);
        handler.broadcast(event("NODE_STARTED"));

        verify(session).close(CloseStatus.POLICY_VIOLATION);
        verify(session, never()).sendMessage(any());
        verify(runRepository, never()).findById(any());
        verify(auditLogService).logSync(eq(42L), eq("SYSTEM"), eq("WS_ACCESS_DENIED"),
                eq("workflow_run"), eq(RUN_ID), any(), eq(null), eq("denied"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "Operator"})
    void invalidDatabaseRoleRevokesOwnerBeforeBroadcast(String role) throws Exception {
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(runStartedBy(42L)));
        WebSocketSession session = session(42L, "operator");
        handler.afterConnectionEstablished(session);
        when(userRepository.findById(42L)).thenReturn(Optional.of(user(role, 1)));

        handler.broadcast(event("NODE_STARTED"));

        verify(session, never()).sendMessage(any());
        verify(session).close(CloseStatus.POLICY_VIOLATION);
        verify(auditLogService).logSync(eq(42L), eq("SYSTEM"), eq("WS_ACCESS_DENIED"),
                eq("workflow_run"), eq(RUN_ID), any(), eq(null), eq("denied"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "Operator"})
    void invalidDatabaseRoleRevokesOwnerBeforePong(String role) throws Exception {
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(runStartedBy(42L)));
        WebSocketSession session = session(42L, "operator");
        handler.afterConnectionEstablished(session);
        when(userRepository.findById(42L)).thenReturn(Optional.of(user(role, 1)));

        handler.handleTextMessage(session, new TextMessage("PING"));

        verify(session, never()).sendMessage(any());
        verify(session).close(CloseStatus.POLICY_VIOLATION);
        verify(auditLogService).logSync(eq(42L), eq("SYSTEM"), eq("WS_ACCESS_DENIED"),
                eq("workflow_run"), eq(RUN_ID), any(), eq(null), eq("denied"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"viewer", "operator", "admin", "super_admin"})
    void validDatabaseRolesAllowOwner(String role) throws Exception {
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(runStartedBy(42L)));
        WebSocketSession session = session(42L, role);

        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage("PING"));

        verify(session, never()).close(any());
        verify(session).sendMessage(org.mockito.ArgumentMatchers.argThat(
                (TextMessage m) -> "PONG".equals(m.getPayload())));
    }

    @Test
    void demotedAdminCannotReceiveOthersEvents() throws Exception {
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(runStartedBy(42L)));
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflowOwnedBy(42L)));
        WebSocketSession session = session(7L, "admin");
        handler.afterConnectionEstablished(session);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user("operator", 1)));

        handler.broadcast(event("NODE_STARTED"));
        verify(session, never()).sendMessage(any());
        verify(session).close(CloseStatus.POLICY_VIOLATION);
        verify(auditLogService).logSync(eq(7L), eq("SYSTEM"), eq("WS_ACCESS_DENIED"),
                eq("workflow_run"), eq(RUN_ID), any(), eq(null), eq("denied"));
    }

    @Test
    void disabledUserCannotReceivePong() throws Exception {
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(runStartedBy(42L)));
        WebSocketSession session = session(42L, "operator");
        handler.afterConnectionEstablished(session);
        when(userRepository.findById(42L)).thenReturn(Optional.of(user("operator", 0)));

        handler.handleTextMessage(session, new TextMessage("PING"));
        verify(session, never()).sendMessage(any());
        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void deletedUserAndDatabaseFailureCloseEstablishedConnections() throws Exception {
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(runStartedBy(42L)));
        WebSocketSession deleted = session(42L, "operator");
        handler.afterConnectionEstablished(deleted);
        when(userRepository.findById(42L)).thenReturn(Optional.empty());
        handler.broadcast(event("NODE_STARTED"));
        verify(deleted, never()).sendMessage(any());
        verify(deleted).close(CloseStatus.POLICY_VIOLATION);

        WebSocketSession failed = session(42L, "operator");
        handler.afterConnectionEstablished(failed);
        when(userRepository.findById(42L)).thenThrow(new RuntimeException("db down"));
        handler.handleTextMessage(failed, new TextMessage("PING"));
        verify(failed, never()).sendMessage(any());
        verify(failed).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void promotedDatabaseRoleDoesNotElevateExistingSession() throws Exception {
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(runStartedBy(7L)));
        WebSocketSession session = session(7L, "operator");
        handler.afterConnectionEstablished(session);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user("admin", 1)));
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(runStartedBy(42L)));
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflowOwnedBy(42L)));
        handler.broadcast(event("NODE_STARTED"));
        verify(session, never()).sendMessage(any());
        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    private WorkflowEvent event(String type) {
        return new WorkflowEvent(type, RUN_ID, "cleanup", "attempt=1", null, Instant.now());
    }

    @Test
    @DisplayName("属主订阅放行，且能收到运行事件")
    void owner_isAllowedAndReceivesEvents() throws Exception {
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(runStartedBy(42L)));
        WebSocketSession session = session(42L, "operator");

        handler.afterConnectionEstablished(session);

        verify(session, never()).close(any());
        handler.broadcast(event("NODE_STARTED"));
        verify(session).sendMessage(org.mockito.ArgumentMatchers.argThat(
                (TextMessage m) -> m.getPayload().contains("NODE_STARTED")));
    }

    @Test
    @DisplayName("越权订阅被拒：会话以 POLICY_VIOLATION 关闭，不注册、收不到事件")
    void nonOwner_isRejectedWithPolicyViolation() throws Exception {
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(runStartedBy(42L)));
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflowOwnedBy(42L)));
        WebSocketSession attacker = session(99L, "operator");

        handler.afterConnectionEstablished(attacker);

        verify(attacker).close(CloseStatus.POLICY_VIOLATION);
        verify(auditLogService).logSync(eq(99L), eq("SYSTEM"), eq("WS_ACCESS_DENIED"),
                eq("workflow_run"), eq(RUN_ID), any(), eq(null), eq("denied"));
        handler.broadcast(event("NODE_SUCCEEDED"));
        verify(attacker, never()).sendMessage(any());
    }

    @Test
    @DisplayName("管理员可订阅他人 run")
    void admin_isAllowedToSubscribeOthersRun() throws Exception {
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(runStartedBy(42L)));
        WebSocketSession admin = session(7L, "super_admin");

        handler.afterConnectionEstablished(admin);

        verify(admin, never()).close(any());
        handler.broadcast(event("RUN_SUCCEEDED"));
        verify(admin).sendMessage(org.mockito.ArgumentMatchers.argThat(
                (TextMessage m) -> m.getPayload().contains("RUN_SUCCEEDED")));
    }

    @Test
    void revokedProvenanceClosesBeforeSending() throws Exception {
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(runStartedBy(42L)));
        when(provenanceService.canView(42L, RUN_ID)).thenReturn(true, false);
        WebSocketSession session = session(42L, "operator");
        handler.afterConnectionEstablished(session);
        handler.broadcast(event("NODE_SUCCEEDED"));
        verify(session, never()).sendMessage(any());
        verify(session).close(CloseStatus.POLICY_VIOLATION);
        verify(auditLogService).logSync(eq(42L), eq("SYSTEM"), eq("WS_ACCESS_DENIED"),
                eq("workflow_run"), eq(RUN_ID), any(), eq(null), eq("denied"));
    }

    @Test
    void unknownProvenanceRejectsAdmin() throws Exception {
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(runStartedBy(42L)));
        when(provenanceService.canView(7L, RUN_ID)).thenReturn(false);
        WebSocketSession session = session(7L, "admin");
        handler.afterConnectionEstablished(session);
        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    @DisplayName("工作流属主（非触发人）可订阅自己工作流上的 run")
    void workflowOwner_isAllowed() throws Exception {
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(runStartedBy(99L)));
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflowOwnedBy(42L)));
        WebSocketSession owner = session(42L, "operator");

        handler.afterConnectionEstablished(owner);

        verify(owner, never()).close(any());
    }

    @Test
    @DisplayName("run 不存在时同样拒绝（fail-closed，不泄露 runId 是否存在）")
    void unknownRun_isRejected() throws Exception {
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.empty());
        WebSocketSession session = session(42L, "operator");

        handler.afterConnectionEstablished(session);

        verify(session).close(CloseStatus.POLICY_VIOLATION);
        handler.broadcast(event("RUN_FAILED"));
        verify(session, never()).sendMessage(any());
    }

    @Test
    @DisplayName("归属查询抛异常时拒绝订阅（fail-closed）")
    void repositoryFailure_deniesSubscription() throws Exception {
        when(runRepository.findById(RUN_ID)).thenThrow(new RuntimeException("db down"));
        WebSocketSession session = session(42L, "operator");

        handler.afterConnectionEstablished(session);

        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    @DisplayName("未认证会话（无 ATTR_USER_ID）仍然拒绝")
    void unauthenticated_isRejected() throws Exception {
        WebSocketSession session = session(null, null);

        handler.afterConnectionEstablished(session);

        verify(session).close(CloseStatus.POLICY_VIOLATION);
        verify(runRepository, never()).findById(any());
    }

    @Test
    @DisplayName("客户端 PING → 服务端 PONG")
    void ping_isAnsweredWithPong() throws Exception {
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(runStartedBy(42L)));
        WebSocketSession session = session(42L, "operator");
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage("PING"));

        verify(session).sendMessage(org.mockito.ArgumentMatchers.argThat(
                (TextMessage m) -> "PONG".equals(m.getPayload())));
    }

    @Test
    @DisplayName("属主关闭连接后不再收到事件")
    void owner_disconnect_stopsReceivingEvents() throws Exception {
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(runStartedBy(42L)));
        WebSocketSession session = session(42L, "operator");
        handler.afterConnectionEstablished(session);
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        handler.broadcast(event("NODE_FAILED"));

        verify(session, never()).sendMessage(any());
    }

    @Test
    @DisplayName("推送的载荷不含节点输出（只放 type/runId/nodeKey/message/at）")
    void broadcast_payloadHasNoNodeOutput() throws Exception {
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(runStartedBy(42L)));
        WebSocketSession session = session(42L, "operator");
        handler.afterConnectionEstablished(session);

        handler.broadcast(event("NODE_SUCCEEDED"));

        verify(session).sendMessage(org.mockito.ArgumentMatchers.argThat((TextMessage m) -> {
            String payload = m.getPayload();
            assertThat(payload).contains("\"type\":\"NODE_SUCCEEDED\"");
            assertThat(payload).doesNotContain("output");
            assertThat(payload).doesNotContain("payload");
            return true;
        }));
    }
}
