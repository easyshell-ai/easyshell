package com.easyshell.server.workflow.node;

import com.easyshell.server.ai.security.SensitiveDataFilter;
import com.easyshell.server.model.entity.WorkflowNodeRun;
import com.easyshell.server.model.entity.WorkflowRun;
import com.easyshell.server.workflow.code.WorkflowCodeWorkerClient;
import com.easyshell.server.workflow.code.WorkflowCodeWorkerClient.ExecutionResult;
import com.easyshell.server.workflow.engine.NodeCancelledException;
import com.easyshell.server.workflow.engine.NodeContext;
import com.easyshell.server.workflow.engine.NodeFailedException;
import com.easyshell.server.workflow.engine.NodeOutcome;
import com.easyshell.server.workflow.engine.VariableStore;
import com.easyshell.server.workflow.expression.ExpressionEvaluator;
import com.easyshell.server.workflow.model.WorkflowDefinition;
import com.easyshell.server.workflow.model.WorkflowNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Remote boundary tests; no Graal runtime, network, TLS files or control plane required. */
class CodeNodeRemoteExecutionTest {
    private static final String SOURCE = "remote-only source placeholder";
    private CodeNodeExecutor executor;
    private WorkflowCodeWorkerClient client;
    private ExecutorService placeholderPool;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        executor = new CodeNodeExecutor(new ExpressionEvaluator(mapper), mapper, new SensitiveDataFilter());
        client = mock(WorkflowCodeWorkerClient.class);
        when(client.isAvailable()).thenReturn(true);
        executor.setWorkerClient(client);
        placeholderPool = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() {
        executor.close();
        placeholderPool.shutdownNow();
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript", "python"})
    void workflowResolvesTemplatesAndFiltersStructuredInputAndOutput(String language) {
        WorkflowNode node = node(language, SOURCE, 5);
        node.getConfig().put("inputs", Map.of(
                "payload", "${nodes.upstream.output}", "label", "count=${nodes.count.output}"));
        VariableStore variables = variables();
        Map<String, Object> expectedInput = Map.of(
                "payload", Map.of("count", 7, "password", "***", "nested", List.of(Map.of("token", "***"))),
                "label", "count=7");
        Map<String, Object> rawOutput = Map.of("answer", 8, "nested", List.of(
                Map.of("apiKey", "output-secret", "message", "password=output-inline")));
        ExecutionResult result = success(rawOutput);
        when(client.execute(eq(language), eq(SOURCE), eq(expectedInput), eq(5000), any(BooleanSupplier.class))).thenReturn(result);

        assertThat(executor.execute(context(node, variables, new AtomicBoolean(), new AtomicBoolean())))
                .isEqualTo(NodeOutcome.SUCCEEDED);

        assertThat(variables.nodeOutput("transform").value()).isEqualTo(Map.of("answer", 8, "nested", List.of(
                Map.of("apiKey", "***", "message", "password=***"))));
        verify(client).execute(eq(language), eq(SOURCE), eq(expectedInput), eq(5000), any(BooleanSupplier.class));
        verify(client).isAvailable();
        verifyNoMoreInteractions(client);
        // Filtering must not mutate the upstream value shared with other nodes.
        assertThat(variables.nodeOutput("upstream").value()).isEqualTo(Map.of("count", 7,
                "password", "input-secret", "nested", List.of(Map.of("token", "nested-input-secret"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript", "python"})
    void executorSampleUsesRemoteResultAndRealFilter(String language) {
        Map<String, Object> rawInput = Map.of("password", "sample-input", "value", 3);
        Map<String, Object> safeInput = Map.of("password", "***", "value", 3);
        ExecutionResult result = success(Map.of("value", 4, "secret", "sample-output"));
        when(client.execute(eq(language), eq(SOURCE), eq(safeInput), eq(1000), any(BooleanSupplier.class))).thenReturn(result);

        assertThat(executor.executeSample(node(language, SOURCE, 1), rawInput))
                .isEqualTo(Map.of("value", 4, "secret", "***"));
        assertThat(rawInput).containsEntry("password", "sample-input");
        ArgumentCaptor<BooleanSupplier> cancellation = ArgumentCaptor.forClass(BooleanSupplier.class);
        verify(client).execute(eq(language), eq(SOURCE), eq(safeInput), eq(1000), cancellation.capture());
        assertThat(cancellation.getValue().getAsBoolean()).isFalse();
    }

    @Test
    void invalidSourceLanguageAndTimeoutNeverSubmit() {
        for (WorkflowNode invalid : List.of(node("ruby", SOURCE, 1), node("python", null, 1),
                node("python", "  ", 1), node("python", "x".repeat(20_001), 1))) {
            assertFailure("NODE_CONFIG_INVALID", () -> executor.executeSample(invalid, Map.of()));
            VariableStore variables = variables();
            assertFailure("NODE_CONFIG_INVALID", () -> executor.execute(
                    context(invalid, variables, new AtomicBoolean(), new AtomicBoolean())));
            assertThat(variables.nodeOutput("transform")).isNull();
        }
        assertFailure("NODE_CONFIG_INVALID", () -> executor.executeSample(node("python", SOURCE, 1), null));
        verify(client, never()).execute(anyString(), anyString(), anyMap(), anyInt(), any(BooleanSupplier.class));
    }

    @Test
    void unsupportedTimeoutsUseExplicitErrorWithoutRemoteSubmission() {
        for (Object timeout : List.of(0, -1, 6, 7, 15, 16, 1.5, "5", Double.NaN, Double.POSITIVE_INFINITY)) {
            WorkflowNode node = node("python", SOURCE, 1);
            node.getConfig().put("timeoutSec", timeout);
            VariableStore variables = variables();
            assertFailure("CODE_TIMEOUT_UNSUPPORTED", () -> executor.executeSample(node, Map.of()));
            assertFailure("CODE_TIMEOUT_UNSUPPORTED", () -> executor.execute(
                    context(node, variables, new AtomicBoolean(), new AtomicBoolean())));
            assertThat(variables.nodeOutput("transform")).isNull();
        }
        verify(client, never()).execute(anyString(), anyString(), anyMap(), anyInt(), any(BooleanSupplier.class));
    }

    @Test
    void scalarArrayAndNullOutputsAreWrappedForWorkflowAndSample() {
        Object[] outputs = {7, true, "plain text", List.of(1, "two", Map.of("token", "array-secret")), null};
        for (Object output : outputs) {
            Map<String, Object> expected = new LinkedHashMap<>();
            expected.put("result", output instanceof List<?> ? List.of(1, "two", Map.of("token", "***")) : output);
            ExecutionResult result = success(output);
            when(client.execute(eq("python"), eq(SOURCE), eq(Map.of()), eq(1000), any(BooleanSupplier.class)))
                    .thenReturn(result);
            VariableStore variables = variables();
            WorkflowNode node = node("python", SOURCE, 1);

            assertThat(executor.execute(context(node, variables, new AtomicBoolean(), new AtomicBoolean())))
                    .isEqualTo(NodeOutcome.SUCCEEDED);
            assertThat(variables.nodeOutput("transform").value()).isEqualTo(expected);
            assertThat(variables.nodeOutput("transform").truncated()).isFalse();
            assertThat(variables.resolve("nodes.transform.output")).contains(expected);
            assertThat(executor.executeSample(node, Map.of())).isEqualTo(expected);
        }
    }

    @Test
    void normalizedResultIsAvailableToDownstreamTemplateAsStructuredData() {
        ExecutionResult first = success(List.of(2, 3));
        ExecutionResult second = success(Map.of("sum", 5));
        when(client.execute(eq("python"), eq(SOURCE), eq(Map.of()), eq(1000), any(BooleanSupplier.class)))
                .thenReturn(first);
        Map<String, Object> downstreamInput = Map.of("previous", Map.of("result", List.of(2, 3)));
        when(client.execute(eq("javascript"), eq(SOURCE), eq(downstreamInput), eq(1000), any(BooleanSupplier.class)))
                .thenReturn(second);
        VariableStore variables = variables();
        executor.execute(context(node("python", SOURCE, 1), variables, new AtomicBoolean(), new AtomicBoolean()));
        WorkflowNode downstream = node("javascript", SOURCE, 1);
        downstream.setKey("downstream");
        downstream.getConfig().put("inputs", Map.of("previous", "${nodes.transform.output}"));

        assertThat(executor.execute(context(downstream, variables, new AtomicBoolean(), new AtomicBoolean())))
                .isEqualTo(NodeOutcome.SUCCEEDED);

        assertThat(variables.nodeOutput("downstream").value()).isEqualTo(Map.of("sum", 5));
        verify(client).execute(eq("javascript"), eq(SOURCE), eq(downstreamInput), eq(1000), any(BooleanSupplier.class));
    }

    @Test
    void exactEightKiBOutputIsCompleteAndOneByteOverIsRejectedForBothEntrypoints() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (boolean wrapped : List.of(false, true)) {
            String field = wrapped ? "result" : "value";
            int overhead = mapper.writeValueAsBytes(Map.of(field, "")).length;
            String text = "x".repeat(8192 - overhead);
            Map<String, Object> expected = Map.of(field, text);
            assertThat(mapper.writeValueAsBytes(expected)).hasSize(8192);
            ExecutionResult atLimit = success(wrapped ? text : expected);
            when(client.execute(eq("python"), eq(SOURCE), eq(Map.of()), eq(1000), any(BooleanSupplier.class)))
                    .thenReturn(atLimit);
            VariableStore variables = variables();
            WorkflowNode node = node("python", SOURCE, 1);

            assertThat(executor.execute(context(node, variables, new AtomicBoolean(), new AtomicBoolean())))
                    .isEqualTo(NodeOutcome.SUCCEEDED);
            assertThat(variables.nodeOutput("transform").value()).isEqualTo(expected);
            assertThat(variables.nodeOutput("transform").truncated()).isFalse();
            assertThat(variables.resolve("nodes.transform.output")).contains(expected);
            assertThat(executor.executeSample(node, Map.of())).isEqualTo(expected);

            ExecutionResult overLimit = success(wrapped ? text + "x" : Map.of(field, text + "x"));
            when(client.execute(eq("python"), eq(SOURCE), eq(Map.of()), eq(1000), any(BooleanSupplier.class)))
                    .thenReturn(overLimit);
            VariableStore rejected = variables();
            assertFailure("CODE_OUTPUT_TOO_LARGE", () -> executor.execute(
                    context(node, rejected, new AtomicBoolean(), new AtomicBoolean())));
            assertThat(rejected.nodeOutput("transform")).isNull();
            assertFailure("CODE_OUTPUT_TOO_LARGE", () -> executor.executeSample(node, Map.of()));
        }
    }

    @Test
    void outputByteCapAppliesAfterFilteringAndCountsUtf8Bytes() {
        WorkflowNode node = node("python", SOURCE, 1);
        ExecutionResult filtered = success(Map.of("password", "x".repeat(9000), "value", 1));
        when(client.execute(eq("python"), eq(SOURCE), eq(Map.of()), eq(1000), any(BooleanSupplier.class)))
                .thenReturn(filtered);
        assertThat(executor.executeSample(node, Map.of())).isEqualTo(Map.of("password", "***", "value", 1));

        ExecutionResult multibyte = success(Map.of("value", "界".repeat(2800)));
        when(client.execute(eq("python"), eq(SOURCE), eq(Map.of()), eq(1000), any(BooleanSupplier.class)))
                .thenReturn(multibyte);
        assertFailure("CODE_OUTPUT_TOO_LARGE", () -> executor.executeSample(node, Map.of()));
        VariableStore variables = variables();
        assertFailure("CODE_OUTPUT_TOO_LARGE", () -> executor.execute(
                context(node, variables, new AtomicBoolean(), new AtomicBoolean())));
        assertThat(variables.nodeOutput("transform")).isNull();
    }

    @Test
    void invalidTemplateConfigurationNeverSubmits() {
        for (Object inputs : List.of("not-an-object", Map.of("bad-name", "value"), Map.of("valid", 123))) {
            WorkflowNode node = node("python", SOURCE, 1);
            node.getConfig().put("inputs", inputs);
            VariableStore variables = variables();
            assertFailure("NODE_CONFIG_INVALID", () -> executor.execute(
                    context(node, variables, new AtomicBoolean(), new AtomicBoolean())));
            assertThat(variables.nodeOutput("transform")).isNull();
        }
        verify(client, never()).execute(anyString(), anyString(), anyMap(), anyInt(), any(BooleanSupplier.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellationBeforeExecutionMakesNoClientRequest(boolean nodeCancellation) {
        VariableStore variables = variables();
        NodeContext context = context(node("python", SOURCE, 1), variables,
                new AtomicBoolean(!nodeCancellation), new AtomicBoolean(nodeCancellation));

        assertThatThrownBy(() -> executor.execute(context)).isInstanceOf(NodeCancelledException.class);

        assertThat(variables.nodeOutput("transform")).isNull();
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellationDuringRemoteCallDiscardsSuccessfulResult(boolean nodeCancellation) {
        VariableStore variables = variables();
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean nodeCancelled = new AtomicBoolean();
        ExecutionResult result = success(Map.of("mustNotPersist", true));
        when(client.execute(eq("python"), eq(SOURCE), eq(Map.of()), eq(1000), any(BooleanSupplier.class))).thenAnswer(invocation -> {
            BooleanSupplier cancellation = invocation.getArgument(4);
            assertThat(cancellation.getAsBoolean()).isFalse();
            (nodeCancellation ? nodeCancelled : cancelled).set(true);
            assertThat(cancellation.getAsBoolean()).isTrue();
            return result;
        });

        assertThatThrownBy(() -> executor.execute(context(node("python", SOURCE, 1), variables,
                cancelled, nodeCancelled))).isInstanceOf(NodeCancelledException.class);

        assertThat(variables.nodeOutput("transform")).isNull();
        ArgumentCaptor<BooleanSupplier> cancellation = ArgumentCaptor.forClass(BooleanSupplier.class);
        verify(client, times(1)).execute(eq("python"), eq(SOURCE), eq(Map.of()), eq(1000), cancellation.capture());
        assertThat(cancellation.getValue().getAsBoolean()).isTrue();
        cancelled.set(false);
        nodeCancelled.set(false);
        assertThat(cancellation.getValue().getAsBoolean()).isFalse();
    }

    @Test
    void unavailableWorkerRejectsBothEntrypointsWithoutSubmission() {
        when(client.isAvailable()).thenReturn(false);
        VariableStore variables = variables();
        WorkflowNode node = node("javascript", SOURCE, 1);

        assertFailure("CODE_RUNTIME_UNAVAILABLE", () -> executor.execute(
                context(node, variables, new AtomicBoolean(), new AtomicBoolean())));
        assertFailure("CODE_RUNTIME_UNAVAILABLE", () -> executor.executeSample(node, Map.of()));

        assertThat(variables.nodeOutput("transform")).isNull();
        verify(client, never()).execute(anyString(), anyString(), anyMap(), anyInt(), any(BooleanSupplier.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CODE_EXECUTION_UNCERTAIN", "UNRECOGNIZED_WORKER_ERROR"})
    void workerFailureIsNotRetriedOrReplacedByLocalExecution(String errorCode) {
        ExecutionResult failed = mock(ExecutionResult.class);
        when(failed.succeeded()).thenReturn(false);
        when(failed.errorCode()).thenReturn(errorCode);
        when(client.execute(eq("python"), eq(SOURCE), eq(Map.of()), eq(1000), any(BooleanSupplier.class))).thenReturn(failed);
        VariableStore variables = variables();

        assertFailure(errorCode, () -> executor.execute(context(node("python", SOURCE, 1), variables,
                new AtomicBoolean(), new AtomicBoolean())));

        assertThat(variables.nodeOutput("transform")).isNull();
        verify(client, times(1)).execute(eq("python"), eq(SOURCE), eq(Map.of()), eq(1000), any(BooleanSupplier.class));
        verify(failed, never()).output();
        verify(client).isAvailable();
        verifyNoMoreInteractions(client);
    }

    @Test
    void failureWithoutErrorCodeUsesStableGenericCodeAndDoesNotRetrySample() {
        ExecutionResult failed = mock(ExecutionResult.class);
        when(client.execute(eq("python"), eq(SOURCE), eq(Map.of()), eq(1000), any(BooleanSupplier.class))).thenReturn(failed);

        assertFailure("CODE_EXECUTION_FAILED", () -> executor.executeSample(node("python", SOURCE, 1), Map.of()));

        verify(client, times(1)).execute(eq("python"), eq(SOURCE), eq(Map.of()), eq(1000), any(BooleanSupplier.class));
        verify(failed, never()).output();
    }

    @Test
    void serverContainsNoLocalSandboxFallback() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/easyshell/server/workflow/node/CodeNodeExecutor.java"));
        assertThat(source).doesNotContain("executeInSandbox", "org.graalvm", "ProcessBuilder", "Runtime.getRuntime()");
    }

    private static ExecutionResult success(Object output) {
        ExecutionResult result = mock(ExecutionResult.class);
        when(result.succeeded()).thenReturn(true);
        when(result.output()).thenReturn(output);
        return result;
    }

    private static void assertFailure(String code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(NodeFailedException.class,
                failure -> assertThat(failure.getCode()).isEqualTo(code));
    }

    private static WorkflowNode node(String language, String source, int timeoutSec) {
        WorkflowNode node = new WorkflowNode();
        node.setKey("transform");
        node.setType("code");
        node.setName("transform");
        node.setTimeoutSec(timeoutSec);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("language", language);
        config.put("code", source);
        config.put("timeoutSec", timeoutSec);
        config.put("inputs", Map.of());
        node.setConfig(config);
        return node;
    }

    private static VariableStore variables() {
        VariableStore variables = new VariableStore();
        variables.putNodeOutput("upstream", null, Map.of("count", 7, "password", "input-secret",
                "nested", List.of(Map.of("token", "nested-input-secret"))));
        variables.putNodeOutput("count", null, 7);
        return variables;
    }

    private NodeContext context(WorkflowNode node, VariableStore variables,
                                AtomicBoolean cancelled, AtomicBoolean nodeCancelled) {
        WorkflowRun run = new WorkflowRun();
        run.setId("wfr_remote_code");
        run.setWorkflowId(1L);
        run.setVersionId(1L);
        run.setVersionNo(1);
        run.setStatus("running");
        run.setStartedBy(42L);
        WorkflowNodeRun nodeRun = new WorkflowNodeRun();
        nodeRun.setId("wnr_remote_code");
        nodeRun.setRunId(run.getId());
        nodeRun.setNodeKey(node.getKey());
        nodeRun.setNodeType("code");
        nodeRun.setAttempt(1);
        nodeRun.setStatus("running");
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setNodes(List.of(node));
        return new NodeContext(run, nodeRun, node, definition, variables, placeholderPool,
                cancelled, nodeCancelled, false, event -> { }, variables);
    }
}
