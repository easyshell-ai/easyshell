package com.easyshell.server.workflow.node;

import com.easyshell.server.ai.channel.ChannelMessageRouter;
import com.easyshell.server.ai.channel.PushOutcome;
import com.easyshell.server.ai.security.SensitiveDataFilter;
import com.easyshell.server.model.entity.WorkflowNodeRun;
import com.easyshell.server.model.entity.WorkflowRun;
import com.easyshell.server.workflow.engine.NodeContext;
import com.easyshell.server.workflow.engine.NodeOutcome;
import com.easyshell.server.workflow.engine.VariableStore;
import com.easyshell.server.workflow.expression.ExpressionEvaluator;
import com.easyshell.server.workflow.model.WorkflowDefinition;
import com.easyshell.server.workflow.model.WorkflowNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * notify 节点的<b>如实上报</b>回归（真机 E2E 缺陷 P-3）。
 *
 * <p>e2e 栈把 {@code ai.channel.dingtalk.enabled} 留成 false，
 * {@link ChannelMessageRouter#pushMessage} 只打一条 WARN 就返回、不抛异常；
 * 旧执行体把"没抛异常"当成"已交付"，于是 {@code node_run.output_json} 写着
 * {@code {"delivered":["dingtalk"]}}——消息从未离开系统，运维拿到的是假信号。</p>
 *
 * <p>这里锁三件事：① 未启用/未注册/无目标的渠道进 {@code skipped} 并带原因码，
 * {@code delivered} 必须为空；② 出站内容仍过 {@link SensitiveDataFilter}；
 * ③ 通知不到不反转 run 结论（节点仍 succeeded）。</p>
 */
@DisplayName("notify 节点 — 推送结果如实上报与出站脱敏（P-3）")
class NotifyNodeExecutorTest {

    private ChannelMessageRouter channelMessageRouter;
    private NotifyNodeExecutor executor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        channelMessageRouter = mock(ChannelMessageRouter.class);
        executor = new NotifyNodeExecutor(channelMessageRouter,
                new ExpressionEvaluator(objectMapper), new SensitiveDataFilter());
    }

    private WorkflowNode node(String template, List<String> channels, Map<String, Object> extra) {
        WorkflowNode node = new WorkflowNode();
        node.setKey("notify");
        node.setType("notify");
        node.setName("notify");
        Map<String, Object> config = new java.util.LinkedHashMap<>();
        config.put("channels", channels);
        config.put("messageTemplate", template);
        config.putAll(extra);
        node.setConfig(config);
        return node;
    }

    private NodeContext context(WorkflowNode node, VariableStore variables) {
        WorkflowRun run = new WorkflowRun();
        run.setId("wfr_notify");
        run.setWorkflowId(1L);
        run.setVersionId(1L);
        run.setVersionNo(1);
        run.setStatus("running");
        run.setStartedBy(42L);
        WorkflowNodeRun nodeRun = new WorkflowNodeRun();
        nodeRun.setId("wnr_notify");
        nodeRun.setRunId(run.getId());
        nodeRun.setNodeKey("notify");
        nodeRun.setNodeType("notify");
        nodeRun.setAttempt(1);
        nodeRun.setStatus("running");
        WorkflowDefinition def = new WorkflowDefinition();
        def.setNodes(List.of(node));
        return new NodeContext(run, nodeRun, node, def, variables,
                java.util.concurrent.Executors.newFixedThreadPool(1), new AtomicBoolean(),
                new AtomicBoolean(), false, event -> {
        }, variables);
    }

    @Test
    @DisplayName("渠道未启用（enabled=false）：delivered 为空 + skipped 带原因码，不谎报已交付")
    void disabledChannel_isReportedAsSkipped_notDelivered() {
        when(channelMessageRouter.pushMessage(anyString(), anyString()))
                .thenReturn(PushOutcome.CHANNEL_NOT_RUNNING);

        VariableStore variables = new VariableStore();
        NodeOutcome outcome = executor.execute(context(node("done: {{FINAL}}",
                List.of("dingtalk"), Map.of("parameters", Map.of("FINAL", "PASS-42"))), variables));

        assertThat(outcome).isEqualTo(NodeOutcome.SUCCEEDED);
        Map<String, Object> output = outputOf(variables);
        // 核心断言：没发出去就绝不在 delivered 里
        assertThat(output.get("delivered")).isEqualTo(List.of());
        assertThat(output.get("failed")).isEqualTo(List.of());
        assertThat(output.get("skipped")).isEqualTo(
                List.of(Map.of("channel", "dingtalk", "reason", "channel_not_running")));
    }

    @Test
    @DisplayName("渠道未注册 / 无推送目标：同样进 skipped，原因码可区分")
    void unknownAndTargetlessChannels_areSkippedWithDistinctReasons() {
        when(channelMessageRouter.pushMessage(eq("typo-channel"), anyString()))
                .thenReturn(PushOutcome.CHANNEL_UNKNOWN);
        when(channelMessageRouter.pushMessage(eq("telegram"), anyString()))
                .thenReturn(PushOutcome.NO_TARGETS);

        VariableStore variables = new VariableStore();
        executor.execute(context(node("done", List.of("typo-channel", "telegram"), Map.of()), variables));

        Map<String, Object> output = outputOf(variables);
        assertThat(output.get("delivered")).isEqualTo(List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, String>> skipped = (List<Map<String, String>>) (List<?>) output.get("skipped");
        assertThat(skipped).containsExactly(
                Map.of("channel", "typo-channel", "reason", "channel_unknown"),
                Map.of("channel", "telegram", "reason", "no_targets"));
    }

    @Test
    @DisplayName("推送已尝试但全部目标失败：进 failed，不进 delivered")
    void pushFailed_isReportedAsFailed() {
        when(channelMessageRouter.pushMessage(anyString(), anyString()))
                .thenReturn(PushOutcome.PUSH_FAILED);

        VariableStore variables = new VariableStore();
        executor.execute(context(node("done", List.of("slack"), Map.of()), variables));

        Map<String, Object> output = outputOf(variables);
        assertThat(output.get("delivered")).isEqualTo(List.of());
        assertThat(output.get("failed")).isEqualTo(List.of("slack"));
        assertThat(output.get("skipped")).isEqualTo(List.of());
    }

    @Test
    @DisplayName("pushMessage 抛异常：按 failed 记录（确实尝试过）")
    void thrownPush_isReportedAsFailed() {
        when(channelMessageRouter.pushMessage(anyString(), anyString()))
                .thenThrow(new IllegalStateException("boom"));

        VariableStore variables = new VariableStore();
        NodeOutcome outcome = executor.execute(context(node("done", List.of("slack"), Map.of()), variables));

        assertThat(outcome).isEqualTo(NodeOutcome.SUCCEEDED);
        assertThat(outputOf(variables).get("failed")).isEqualTo(List.of("slack"));
    }

    @Test
    @DisplayName("推送结果缺失（null）：fail-closed 按未送达处理，绝不进 delivered")
    void nullOutcome_isNotDelivered() {
        // Mockito 对枚举默认返回 null；这条用例同时是"未知结果不得谎报"的闸门
        when(channelMessageRouter.pushMessage(anyString(), anyString())).thenReturn(null);

        VariableStore variables = new VariableStore();
        executor.execute(context(node("done", List.of("dingtalk"), Map.of()), variables));

        Map<String, Object> output = outputOf(variables);
        assertThat(output.get("delivered")).isEqualTo(List.of());
        assertThat(output.get("failed")).isEqualTo(List.of("dingtalk"));
    }

    @Test
    @DisplayName("渠道可用：正常进 delivered（回归：不改成功路径）")
    void deliveredChannel_isReportedAsDelivered() {
        when(channelMessageRouter.pushMessage(anyString(), anyString()))
                .thenReturn(PushOutcome.DELIVERED);

        VariableStore variables = new VariableStore();
        executor.execute(context(node("done", List.of("telegram"), Map.of()), variables));

        Map<String, Object> output = outputOf(variables);
        assertThat(output.get("delivered")).isEqualTo(List.of("telegram"));
        assertThat(output.get("failed")).isEqualTo(List.of());
        assertThat(output.get("skipped")).isEqualTo(List.of());
    }

    @Test
    @DisplayName("出站脱敏：模板渲染出的 token=... 在推送前已被掩码（出口 17 不削弱）")
    void sensitiveContent_isMaskedBeforePush() {
        when(channelMessageRouter.pushMessage(anyString(), anyString()))
                .thenReturn(PushOutcome.DELIVERED);

        VariableStore variables = new VariableStore();
        executor.execute(context(node("deploy token=sk-abcdefghijklmnopqrstuvwxyz012345",
                List.of("dingtalk"), Map.of()), variables));

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(channelMessageRouter).pushMessage(anyString(), content.capture());
        assertThat(content.getValue()).contains("***");
        assertThat(content.getValue()).doesNotContain("sk-abcdefghijklmnopqrstuvwxyz012345");
    }

    @Test
    @DisplayName("level 前缀仍拼在最前面（脱敏之后、推送之前）")
    void levelPrefixIsPrepended() {
        when(channelMessageRouter.pushMessage(anyString(), anyString()))
                .thenReturn(PushOutcome.DELIVERED);

        VariableStore variables = new VariableStore();
        executor.execute(context(node("disk almost full", List.of("dingtalk"),
                Map.of("level", "warning")), variables));

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(channelMessageRouter).pushMessage(anyString(), content.capture());
        assertThat(content.getValue()).isEqualTo("[warning] disk almost full");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static Map<String, Object> outputOf(VariableStore variables) {
        Object value = variables.nodeOutput("notify").value();
        return value instanceof Map<?, ?> m ? cast(m) : Map.of();
    }
}
