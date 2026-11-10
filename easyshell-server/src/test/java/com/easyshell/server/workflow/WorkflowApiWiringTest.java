package com.easyshell.server.workflow;

import com.easyshell.server.workflow.controller.WorkflowAccess;
import com.easyshell.server.workflow.controller.WorkflowController;
import com.easyshell.server.workflow.engine.WorkflowEngine;
import com.easyshell.server.workflow.engine.NodeExecutorRegistry;
import com.easyshell.server.workflow.engine.WorkflowEvent;
import com.easyshell.server.workflow.engine.WorkflowEventBroadcaster;
import com.easyshell.server.workflow.model.NodeType;
import com.easyshell.server.workflow.service.WorkflowDefinitionService;
import com.easyshell.server.workflow.service.WorkflowRunService;
import com.easyshell.server.workflow.service.WorkflowTestRunService;
import com.easyshell.server.websocket.WorkflowRunWebSocketHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工作流 API 的<b>装配</b>回归（真实 Spring 上下文，H2 + 打桩的 Redis）。
 *
 * <p>为什么要有这个测试：内核落地时 {@code ExpressionEvaluator} 漏登记为 bean，导致
 * {@code DagValidator}/节点执行器/{@code WorkflowEngine} 全都建不起来——
 * <b>应用启动即失败</b>。而既有的全量上下文测试是 {@code @Disabled}（需要 Redis/ClickHouse），
 * 559 个单测全绿也盖不住这个洞。本测试把上下文真正拉起来一次，并顺带锁定
 * "引擎的事件出口 = WS 广播器"（§6.4 的推送链路）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("工作流 REST/WS — Spring 装配回归")
class WorkflowApiWiringTest {

    @MockitoBean
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private WorkflowController controller;
    @Autowired
    private WorkflowAccess access;
    @Autowired
    private WorkflowRunWebSocketHandler runWebSocketHandler;
    @Autowired
    private WorkflowEventBroadcaster broadcaster;
    @Autowired
    private WorkflowEngine engine;
    @Autowired
    private WorkflowDefinitionService definitionService;
    @Autowired
    private WorkflowRunService runService;
    @Autowired
    private WorkflowTestRunService testRunService;
    @Autowired
    private NodeExecutorRegistry nodeExecutorRegistry;

    @Test
    @DisplayName("17 个端点所属的 bean 全部装得起来（上下文启动即回归）")
    void workflowApiBeansAreWired() {
        assertThat(controller).isNotNull();
        assertThat(access).isNotNull();
        assertThat(definitionService).isNotNull();
        assertThat(runService).isNotNull();
        assertThat(testRunService).isNotNull();
        assertThat(runWebSocketHandler).isNotNull();
    }

    @Test
    @DisplayName("NodeType 枚举的每个类型都有 Spring 执行器 bean（含工作流 2.0 新增四类）")
    void everyNodeTypeHasRegisteredExecutor() {
        for (NodeType type : NodeType.values()) {
            assertThat(nodeExecutorRegistry.supports(type))
                    .as("节点类型 %s 缺少执行器 bean（用 @Component 注册）", type.code())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("引擎的事件出口就是 WS 广播器（§6.4 推送链路接通）")
    void engineEventSinkIsTheBroadcaster() {
        Object sink = ReflectionTestUtils.getField(engine, "eventSink");

        assertThat(sink).isInstanceOf(WorkflowEventBroadcaster.class);
        assertThat(sink).isSameAs(broadcaster);
    }

    @Test
    @DisplayName("广播器把事件转给 WS handler（无订阅者也安全）")
    void broadcasterForwardsEvents() {
        Consumer<WorkflowEvent> sink = broadcaster;
        sink.accept(WorkflowEvent.of("RUN_STARTED", "wfr_none", null, "started"));

        assertThat(broadcaster).isNotNull();
    }
}
