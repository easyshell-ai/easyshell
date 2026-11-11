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
import com.easyshell.server.workflow.graph.ValidationIssue;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ai-analyze 节点 — 脱敏 / 配额 / 超时 / 不产出可执行内容")
class AiAnalyzeNodeExecutorTest {

    private ChatModelFactory chatModelFactory;
    private ChatModel chatModel;
    private AiQuotaService aiQuotaService;
    private ExecutorService aiPool;
    private AiAnalyzeNodeExecutor executor;

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
        executor = new AiAnalyzeNodeExecutor(chatModelFactory, new SensitiveDataFilter(),
                aiQuotaService, new ExpressionEvaluator(objectMapper), aiPool,
                new WorkflowProperties(), objectMapper);
    }

    @AfterEach
    void tearDown() {
        aiPool.shutdownNow();
    }

    private WorkflowNode node(String instructions, String input, int maxInputChars, int timeoutSec) {
        WorkflowNode node = new WorkflowNode();
        node.setKey("ai");
        node.setType("ai-analyze");
        node.setName("ai");
        node.setTimeoutSec(timeoutSec);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("instructions", instructions);
        config.put("input", input);
        config.put("maxInputChars", maxInputChars);
        node.setConfig(config);
        return node;
    }

    private NodeContext context(WorkflowNode node, VariableStore variables) {
        WorkflowRun run = new WorkflowRun();
        run.setId("wfr_ai");
        run.setWorkflowId(1L);
        run.setVersionId(1L);
        run.setVersionNo(1);
        run.setStatus("running");
        run.setStartedBy(42L);

        WorkflowNodeRun nodeRun = new WorkflowNodeRun();
        nodeRun.setId("wnr_ai");
        nodeRun.setRunId(run.getId());
        nodeRun.setNodeKey(node.getKey());
        nodeRun.setNodeType("ai-analyze");
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
    @DisplayName("成功：输入/输出都脱敏，计入配额，output={text, model, truncated}")
    void successMasksInputAndOutputAndCountsQuota() {
        when(chatModelFactory.getChatModel((String) null)).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class))).thenReturn(response("结论：token=abc123 需要关注"));

        VariableStore variables = new VariableStore();
        variables.putNodeOutput("up", null, "mount=/data password=hunter2");

        WorkflowNode node = node("分析磁盘输出", "${nodes.up.output}", 1000, 60);
        assertThat(executor.execute(context(node, variables))).isEqualTo(NodeOutcome.SUCCEEDED);

        // 输入先脱敏：模型看到的 prompt 不含明文密码
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        List<Message> messages = prompt.getValue().getInstructions();
        String promptText = String.join("\n", messages.stream().map(Message::getText).toList());
        assertThat(promptText).doesNotContain("hunter2");
        assertThat(promptText).contains("***");

        // 输出也脱敏
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) variables.nodeOutput("ai").value();
        assertThat(output.get("model")).isEqualTo("default");
        assertThat(output.get("truncated")).isEqualTo(false);
        assertThat((String) output.get("text")).doesNotContain("abc123");
        assertThat((String) output.get("text")).contains("***");

        verify(aiQuotaService).checkAndIncrement(eq(42L), eq("workflow-ai-analyze"));
    }

    @Test
    @DisplayName("outputMode=json：剥离围栏解析，output 增加 json/structured")
    void jsonOutputModeAddsStructuredFields() {
        when(chatModelFactory.getChatModel((String) null)).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(response("```json\n{\"risk\":\"high\",\"score\":90}\n```"));

        WorkflowNode node = node("分析", "", 1000, 60);
        node.getConfig().put("outputMode", "json");
        VariableStore variables = new VariableStore();
        executor.execute(context(node, variables));

        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) variables.nodeOutput("ai").value();
        assertThat(output.get("structured")).isEqualTo(true);
        assertThat(output.get("json")).isInstanceOf(Map.class);
        assertThat(output.get("truncated")).isEqualTo(false);
        assertThat(output.get("text")).isInstanceOf(String.class);
    }

    @Test
    @DisplayName("input 支持纯路径（无 ${}）：与 DagValidator 的静态识别一致")
    void barePathInputIsResolved() {
        when(chatModelFactory.getChatModel((String) null)).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class))).thenReturn(response("ok"));

        VariableStore variables = new VariableStore();
        variables.putNodeOutput("up", null, Map.of("text", "disk-ok"));

        WorkflowNode node = node("分析", "nodes.up.output.text", 1000, 60);
        executor.execute(context(node, variables));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        String promptText = String.join("\n", prompt.getValue().getInstructions().stream()
                .map(org.springframework.ai.chat.messages.Message::getText).toList());
        assertThat(promptText).contains("disk-ok");
    }

    @Test
    @DisplayName("模型解析失败：不消耗配额（先取模型后计费）")
    void modelFailureDoesNotConsumeQuota() {
        when(chatModelFactory.getChatModel(isNull()))
                .thenThrow(new BusinessException(400, "未配置默认 Provider"));

        WorkflowNode node = node("分析", "", 1000, 60);
        assertThatThrownBy(() -> executor.execute(context(node, new VariableStore())))
                .isInstanceOfSatisfying(NodeFailedException.class, e ->
                        assertThat(e.getCode()).isEqualTo("AI_UNAVAILABLE"));
        verify(aiQuotaService, never()).checkAndIncrement(any(), any());
    }

    @Test
    @DisplayName("输入超过 maxInputChars：截断并标记 truncated=true")
    void longInputIsTruncated() {
        when(chatModelFactory.getChatModel((String) null)).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class))).thenReturn(response("ok"));

        VariableStore variables = new VariableStore();
        variables.putNodeOutput("up", null, "x".repeat(1500));

        WorkflowNode node = node("分析", "${nodes.up.output}", 1000, 60);
        executor.execute(context(node, variables));

        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) variables.nodeOutput("ai").value();
        assertThat(output.get("truncated")).isEqualTo(true);
    }

    @Test
    @DisplayName("模型调用超时：AI_TIMEOUT 且 future 被取消（不无限等）")
    void timeoutCancelsModelCall() {
        when(chatModelFactory.getChatModel((String) null)).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            Thread.sleep(5000);
            return response("late");
        });

        WorkflowNode node = node("分析", "", 1000, 1);
        long started = System.currentTimeMillis();
        assertThatThrownBy(() -> executor.execute(context(node, new VariableStore())))
                .isInstanceOfSatisfying(NodeFailedException.class, e ->
                        assertThat(e.getCode()).isEqualTo("AI_TIMEOUT"));
        assertThat(System.currentTimeMillis() - started).isLessThan(4000);
    }

    @Test
    @DisplayName("线程池准入失败：退回已扣配额（AI_UNAVAILABLE）")
    void poolAdmissionFailureRefundsQuota() {
        when(chatModelFactory.getChatModel((String) null)).thenReturn(chatModel);
        aiPool.shutdownNow();

        WorkflowNode node = node("分析", "", 1000, 60);
        assertThatThrownBy(() -> executor.execute(context(node, new VariableStore())))
                .isInstanceOfSatisfying(NodeFailedException.class, e ->
                        assertThat(e.getCode()).isEqualTo("AI_UNAVAILABLE"));
        verify(aiQuotaService).checkAndIncrement(42L, "workflow-ai-analyze");
        verify(aiQuotaService).refund(42L);
    }

    @Test
    @DisplayName("配额超限：AI_QUOTA_EXCEEDED，且不调用模型")
    void quotaExceededFailsWithoutModelCall() {
        when(chatModelFactory.getChatModel((String) null)).thenReturn(chatModel);
        org.mockito.Mockito.doThrow(new BusinessException(429, "quota exceeded"))
                .when(aiQuotaService).checkAndIncrement(42L, "workflow-ai-analyze");

        WorkflowNode node = node("分析", "", 1000, 60);
        assertThatThrownBy(() -> executor.execute(context(node, new VariableStore())))
                .isInstanceOfSatisfying(NodeFailedException.class, e ->
                        assertThat(e.getCode()).isEqualTo("AI_QUOTA_EXCEEDED"));
    }

    @Test
    @DisplayName("配置校验：instructions 必填、maxInputChars 范围 1000..32000")
    void validatesConfig() {
        ValidationIssue.Sink sink = new ValidationIssue.Sink();
        executor.validate(node("", "", 999, 60), new WorkflowDefinition(), sink);
        assertThat(sink.hasError()).isTrue();
        assertThat(sink.issues()).anySatisfy(i -> assertThat(i.message()).contains("instructions"));
        assertThat(sink.issues()).anySatisfy(i -> assertThat(i.message()).contains("maxInputChars"));

        ValidationIssue.Sink ok = new ValidationIssue.Sink();
        executor.validate(node("分析", "", 8000, 60), new WorkflowDefinition(), ok);
        assertThat(ok.hasError()).isFalse();
    }

    @Test
    void legacyJsonRequestsMaskInstructionsSchemaInputAndRepair() {
        when(chatModelFactory.getChatModel((String) null)).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(response("invalid token=previoussecret"), response("{\"ok\":true}"));
        WorkflowNode node = node("password=instructionsecret", "token=inputsecret", 1000, 60);
        node.getConfig().put("outputMode", "json");
        node.getConfig().put("jsonSchema", "password=schemasecret");
        executor.execute(context(node, new VariableStore()));
        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(2)).call(prompts.capture());
        for (Prompt prompt : prompts.getAllValues()) {
            String text = String.join("\n", prompt.getInstructions().stream().map(Message::getText).toList());
            assertThat(text).contains("***").doesNotContain("instructionsecret", "inputsecret", "schemasecret", "previoussecret");
        }
    }

    @Test
    void strictSchemaFailureDoesNotPublishOutput() {
        String schema = "{\"type\":\"object\",\"properties\":{\"ok\":{\"type\":\"boolean\"}},\"required\":[\"ok\"],\"additionalProperties\":false}";
        when(chatModelFactory.getChatModel((String) null)).thenReturn(chatModel);
        WorkflowNode node = node("password=instructionsecret", "token=inputsecret", 1000, 60);
        node.getConfig().put("outputMode", "json_schema_v1");
        node.getConfig().put("jsonSchema", schema);
        when(chatModel.call(any(Prompt.class))).thenReturn(response("{\"ok\":\"wrong\"}"));
        VariableStore variables = new VariableStore();
        assertThatThrownBy(() -> executor.execute(context(node, variables)))
                .isInstanceOfSatisfying(NodeFailedException.class, e ->
                        assertThat(e.getCode()).isEqualTo("AI_OUTPUT_INVALID_JSON"));
        assertThat(variables.nodeOutput("ai")).isNull();
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        String text = String.join("\n", prompt.getValue().getInstructions().stream().map(Message::getText).toList());
        assertThat(text).contains("***", "additionalProperties").doesNotContain("instructionsecret", "inputsecret");
    }
}
