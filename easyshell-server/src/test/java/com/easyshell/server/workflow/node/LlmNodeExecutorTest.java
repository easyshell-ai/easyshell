package com.easyshell.server.workflow.node;

import com.easyshell.server.ai.security.AiQuotaService;
import com.easyshell.server.ai.security.SensitiveDataFilter;
import com.easyshell.server.ai.service.ChatModelFactory;
import com.easyshell.server.common.exception.BusinessException;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("llm 节点 — text/json、JSON 修复、脱敏、配额")
class LlmNodeExecutorTest {

    private ChatModelFactory chatModelFactory;
    private ChatModel chatModel;
    private AiQuotaService aiQuotaService;
    private ExecutorService aiPool;
    private LlmNodeExecutor executor;

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
        executor = new LlmNodeExecutor(chatModelFactory, new SensitiveDataFilter(), aiQuotaService,
                new ExpressionEvaluator(objectMapper), aiPool, new WorkflowProperties(), objectMapper);
        when(chatModelFactory.getChatModel((String) null, (String) null, (Double) null))
                .thenReturn(chatModel);
    }

    @AfterEach
    void tearDown() {
        aiPool.shutdownNow();
    }

    private WorkflowNode node(String userPrompt, String outputMode, Map<String, Object> extra) {
        WorkflowNode node = new WorkflowNode();
        node.setKey("llm");
        node.setType("llm");
        node.setName("llm");
        node.setTimeoutSec(30);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("userPrompt", userPrompt);
        config.put("outputMode", outputMode);
        config.put("maxInputChars", 1000);
        if (extra != null) {
            config.putAll(extra);
        }
        node.setConfig(config);
        return node;
    }

    private NodeContext context(WorkflowNode node, VariableStore variables) {
        WorkflowRun run = new WorkflowRun();
        run.setId("wfr_llm");
        run.setWorkflowId(1L);
        run.setVersionId(1L);
        run.setVersionNo(1);
        run.setStatus("running");
        run.setStartedBy(42L);

        WorkflowNodeRun nodeRun = new WorkflowNodeRun();
        nodeRun.setId("wnr_llm");
        nodeRun.setRunId(run.getId());
        nodeRun.setNodeKey(node.getKey());
        nodeRun.setNodeType("llm");
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> output(VariableStore variables) {
        return (Map<String, Object>) variables.nodeOutput("llm").value();
    }

    @Test
    @DisplayName("text：输入/输出都脱敏，计入 workflow-llm 配额，output={text,json,structured,model}")
    void textModeMasksInputAndOutput() {
        when(chatModel.call(any(Prompt.class))).thenReturn(response("分析结果 token=abc123"));

        VariableStore variables = new VariableStore();
        variables.putNodeOutput("up", null, "mount=/data password=hunter2");

        WorkflowNode node = node("分析 ${nodes.up.output}", "text", null);
        assertThat(executor.execute(context(node, variables))).isEqualTo(NodeOutcome.SUCCEEDED);

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        String promptText = String.join("\n", prompt.getValue().getInstructions().stream()
                .map(Message::getText).toList());
        assertThat(promptText).doesNotContain("hunter2");
        assertThat(promptText).contains("***");

        Map<String, Object> output = output(variables);
        assertThat(output.get("model")).isEqualTo("default");
        assertThat(output.get("json")).isNull();
        assertThat(output.get("structured")).isEqualTo(false);
        assertThat((String) output.get("text")).doesNotContain("abc123");
        assertThat((String) output.get("text")).contains("***");
        verify(aiQuotaService).checkAndIncrement(eq(42L), eq("workflow-llm"));
    }

    @Test
    @DisplayName("json：剥离 Markdown 围栏后解析，structured=true，json 是结构化对象")
    void jsonModeParsesFencedJson() {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(response("```json\n{\"summary\":\"disk ok\",\"score\":90}\n```"));

        VariableStore variables = new VariableStore();
        WorkflowNode node = node("输出 JSON", "json", Map.of("jsonSchema", "{\"type\":\"object\"}"));
        executor.execute(context(node, variables));

        Map<String, Object> output = output(variables);
        assertThat(output.get("structured")).isEqualTo(true);
        assertThat(output.get("json")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> json = (Map<String, Object>) output.get("json");
        assertThat(json.get("summary")).isEqualTo("disk ok");
        assertThat(((Number) json.get("score")).intValue()).isEqualTo(90);
        verify(chatModel, times(1)).call(any(Prompt.class));
    }

    @Test
    @DisplayName("json 非法：一次 repair 成功")
    void invalidJsonIsRepairedOnce() {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(response("这不是 JSON"))
                .thenReturn(response("{\"ok\":true}"));

        VariableStore variables = new VariableStore();
        executor.execute(context(node("输出 JSON", "json", null), variables));

        Map<String, Object> output = output(variables);
        assertThat(output.get("structured")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> json = (Map<String, Object>) output.get("json");
        assertThat(json.get("ok")).isEqualTo(true);
        verify(chatModel, times(2)).call(any(Prompt.class));
        verify(aiQuotaService).checkAndIncrement(42L, "workflow-llm");
        verify(aiQuotaService).checkAndIncrement(42L, "workflow-llm-repair");
    }

    @Test
    @DisplayName("json 连续非法：repair 一次后仍失败 → AI_OUTPUT_INVALID_JSON")
    void invalidJsonFailsAfterOneRepair() {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(response("不是 JSON"))
                .thenReturn(response("仍然不是 JSON"));

        VariableStore variables = new VariableStore();
        assertThatThrownBy(() -> executor.execute(context(node("输出 JSON", "json", null), variables)))
                .isInstanceOfSatisfying(NodeFailedException.class, e ->
                        assertThat(e.getCode()).isEqualTo("AI_OUTPUT_INVALID_JSON"));
        verify(chatModel, times(2)).call(any(Prompt.class));
        assertThat(variables.nodeOutput("llm")).isNull();
    }

    @Test
    @DisplayName("配额超限：AI_QUOTA_EXCEEDED，且不调用模型")
    void quotaExceededDoesNotCallModel() {
        doThrow(new BusinessException(429, "quota exceeded"))
                .when(aiQuotaService).checkAndIncrement(42L, "workflow-llm");

        assertThatThrownBy(() -> executor.execute(context(node("x", "text", null), new VariableStore())))
                .isInstanceOfSatisfying(NodeFailedException.class, e ->
                        assertThat(e.getCode()).isEqualTo("AI_QUOTA_EXCEEDED"));
        verify(chatModel, never()).call(any(Prompt.class));
    }
}
