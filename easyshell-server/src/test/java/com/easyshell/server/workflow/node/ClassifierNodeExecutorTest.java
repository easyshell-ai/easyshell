package com.easyshell.server.workflow.node;

import com.easyshell.server.ai.security.AiQuotaService;
import com.easyshell.server.ai.security.SensitiveDataFilter;
import com.easyshell.server.ai.service.ChatModelFactory;
import com.easyshell.server.model.entity.WorkflowNodeRun;
import com.easyshell.server.model.entity.WorkflowRun;
import com.easyshell.server.workflow.engine.NodeContext;
import com.easyshell.server.workflow.engine.NodeFailedException;
import com.easyshell.server.workflow.engine.NodeOutcome;
import com.easyshell.server.workflow.engine.VariableStore;
import com.easyshell.server.workflow.expression.ExpressionEvaluator;
import com.easyshell.server.workflow.model.WorkflowDefinition;
import com.easyshell.server.workflow.model.WorkflowNode;
import com.easyshell.server.workflow.properties.WorkflowProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("classifier 节点 — 合法类别 / 非法类别 fail-closed / 脱敏")
class ClassifierNodeExecutorTest {

    private ChatModelFactory chatModelFactory;
    private ChatModel chatModel;
    private AiQuotaService aiQuotaService;
    private ExecutorService aiPool;
    private ClassifierNodeExecutor executor;

    @BeforeEach
    void setUp() {
        chatModelFactory = mock(ChatModelFactory.class);
        chatModel = mock(ChatModel.class);
        aiQuotaService = mock(AiQuotaService.class);
        aiPool = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "workflow-ai-test");
            thread.setDaemon(true);
            return thread;
        });
        ObjectMapper objectMapper = new ObjectMapper();
        executor = new ClassifierNodeExecutor(chatModelFactory, new SensitiveDataFilter(), aiQuotaService,
                new ExpressionEvaluator(objectMapper), aiPool, new WorkflowProperties(), objectMapper);
        when(chatModelFactory.getChatModel((String) null, (String) null, (Double) null))
                .thenReturn(chatModel);
    }

    @AfterEach
    void tearDown() {
        aiPool.shutdownNow();
    }

    private WorkflowNode node() {
        WorkflowNode node = new WorkflowNode();
        node.setKey("classify");
        node.setType("classifier");
        node.setName("classify");
        node.setTimeoutSec(30);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("input", "${nodes.up.output}");
        config.put("classes", List.of(
                Map.of("name", "disk", "description", "磁盘/容量问题"),
                Map.of("name", "network", "description", "网络/端口问题")));
        node.setConfig(config);
        return node;
    }

    private NodeContext context(WorkflowNode node, VariableStore variables) {
        WorkflowRun run = new WorkflowRun();
        run.setId("wfr_classify");
        run.setWorkflowId(1L);
        run.setVersionId(1L);
        run.setVersionNo(1);
        run.setStatus("running");
        run.setStartedBy(42L);

        WorkflowNodeRun nodeRun = new WorkflowNodeRun();
        nodeRun.setId("wnr_classify");
        nodeRun.setRunId(run.getId());
        nodeRun.setNodeKey(node.getKey());
        nodeRun.setNodeType("classifier");
        nodeRun.setAttempt(1);
        nodeRun.setStatus("running");

        WorkflowDefinition def = new WorkflowDefinition();
        def.setNodes(List.of(node));
        return new NodeContext(run, nodeRun, node, def, variables, aiPool,
                new AtomicBoolean(), new AtomicBoolean(), false, event -> {
        }, variables);
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    @Test
    @DisplayName("合法类别：输出 {class,reason,raw}，计入 workflow-classifier 配额，提示词含类别名")
    void validClassSucceeds() {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(response("{\"class\":\"disk\",\"reason\":\"mount full token=abc123\"}"));

        VariableStore variables = new VariableStore();
        variables.putNodeOutput("up", null, "df -h says /data is full password=hunter2");
        WorkflowNode node = node();
        assertThat(executor.execute(context(node, variables))).isEqualTo(NodeOutcome.SUCCEEDED);

        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) variables.nodeOutput("classify").value();
        assertThat(output.get("class")).isEqualTo("disk");
        assertThat((String) output.get("reason")).contains("***").doesNotContain("abc123");
        assertThat((String) output.get("raw")).contains("***");
        verify(aiQuotaService).checkAndIncrement(eq(42L), eq("workflow-classifier"));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        String promptText = String.join("\n", prompt.getValue().getInstructions().stream()
                .map(Message::getText).toList());
        assertThat(promptText).contains("disk").contains("network");
        assertThat(promptText).doesNotContain("hunter2");
    }

    @Test
    @DisplayName("class 不在配置类别：AI_CLASSIFY_INVALID（fail-closed，不产出输出）")
    void unknownClassFailsClosed() {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(response("{\"class\":\"storage\",\"reason\":\"x\"}"));

        VariableStore variables = new VariableStore();
        variables.putNodeOutput("up", null, "df -h");
        assertThatThrownBy(() -> executor.execute(context(node(), variables)))
                .isInstanceOfSatisfying(NodeFailedException.class, e ->
                        assertThat(e.getCode()).isEqualTo("AI_CLASSIFY_INVALID"));
        assertThat(variables.nodeOutput("classify")).isNull();
    }

    @Test
    @DisplayName("输出不是 JSON：AI_CLASSIFY_INVALID（fail-closed）")
    void unparseableOutputFailsClosed() {
        when(chatModel.call(any(Prompt.class))).thenReturn(response("disk please"));
        VariableStore variables = new VariableStore();
        variables.putNodeOutput("up", null, "df -h");

        assertThatThrownBy(() -> executor.execute(context(node(), variables)))
                .isInstanceOfSatisfying(NodeFailedException.class, e ->
                        assertThat(e.getCode()).isEqualTo("AI_CLASSIFY_INVALID"));
    }
}
