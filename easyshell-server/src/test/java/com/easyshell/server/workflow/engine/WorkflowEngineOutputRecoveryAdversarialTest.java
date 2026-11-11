package com.easyshell.server.workflow.engine;

import com.easyshell.server.ai.security.SensitiveDataFilter;
import com.easyshell.server.model.entity.WorkflowNodeRun;
import com.easyshell.server.model.entity.WorkflowRun;
import com.easyshell.server.repository.JobRepository;
import com.easyshell.server.repository.WorkflowApprovalRepository;
import com.easyshell.server.repository.WorkflowNodeRunRepository;
import com.easyshell.server.repository.WorkflowRepository;
import com.easyshell.server.repository.WorkflowRunRepository;
import com.easyshell.server.repository.WorkflowVersionRepository;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.workflow.expression.ExpressionEvaluator;
import com.easyshell.server.workflow.graph.DagValidator;
import com.easyshell.server.workflow.properties.WorkflowProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Recovery must prove a persisted output is complete before executing a descendant. */
class WorkflowEngineOutputRecoveryAdversarialTest {
    private static final String DEFINITION = """
            {"schemaVersion":1,"nodes":[
              {"key":"source","type":"transform","config":{}},
              {"key":"effect","type":"notify","config":{}}],
             "edges":[{"id":"e","from":"source","to":"effect"}]}
            """;

    private final WorkflowRunRepository runs = mock(WorkflowRunRepository.class);
    private final WorkflowNodeRunRepository nodes = mock(WorkflowNodeRunRepository.class);
    private final WorkflowVersionRepository versions = mock(WorkflowVersionRepository.class);
    private final NodeExecutorRegistry registry = mock(NodeExecutorRegistry.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private ExecutorService pool;
    private WorkflowEngine engine;
    private WorkflowRun run;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        ExpressionEvaluator evaluator = new ExpressionEvaluator(mapper);
        pool = Executors.newSingleThreadExecutor();
        when(transactions.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        engine = new WorkflowEngine(runs, nodes, mock(WorkflowApprovalRepository.class), versions,
                mock(WorkflowRepository.class), mock(JobRepository.class), registry,
                new DagValidator(evaluator), evaluator, mock(AttemptAllocator.class),
                mock(AuditLogService.class), new SensitiveDataFilter(), Optional.empty(), pool,
                mock(org.springframework.scheduling.TaskScheduler.class), new WorkflowProperties(),
                mapper, transactions);
        run = new WorkflowRun();
        run.setId("recovery-test");
        run.setVersionId(1L);
        run.setStatus("running");
        run.setInputJson("{}");
        when(runs.findById(run.getId())).thenReturn(Optional.of(run));
        when(versions.findDefinition(1L)).thenReturn(Optional.of(DEFINITION));
        // Reaching any executor is a test failure, even if execution subsequently throws.
        when(registry.executorFor(any())).thenThrow(new AssertionError("downstream executor reached"));
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    static Stream<Arguments> unprovableOutputs() throws Exception {
        String json = "{\"value\":1}";
        return Stream.of(
                // A valid JSON value can still be only a preview of a different full output.
                Arguments.of("parseable preview", "true", sha("a different full output"), sha("true")),
                Arguments.of("full digest mismatch", json, sha("{\"value\":2}"), null),
                Arguments.of("missing digest", json, null, null),
                Arguments.of("malformed JSON", "{\"value\":", sha("{\"value\":"), null),
                Arguments.of("trailing JSON", "{} {}", sha("{} {}"), null),
                Arguments.of("noncanonical JSON", "{ \"value\": 1 }", sha("{ \"value\": 1 }"), null),
                Arguments.of("preview marker even with matching digest", json, sha(json), sha(json)),
                Arguments.of("missing output", null, null, null));
    }

    @ParameterizedTest(name = "{0} must stop recovery before downstream execution")
    @MethodSource("unprovableOutputs")
    void rejectsUnprovableOutput(String scenario, String json, String outputSha, String previewSha) {
        WorkflowNodeRun source = source(json, outputSha, previewSha);
        when(nodes.findByRunId(run.getId())).thenReturn(List.of(source));
        // The first definition read is recovery; driveLocked reads it again. Crossing
        // that boundary means recovery incorrectly allowed execution to proceed. Stop
        // synchronously before the mocked persistence layer can make drive loop forever.
        when(versions.findDefinition(1L)).thenReturn(Optional.of(DEFINITION))
                .thenThrow(new AssertionError("unprovable output passed the recovery boundary: " + scenario));

        assertThatCode(() -> engine.drive(run)).doesNotThrowAnyException();

        verify(runs).finishRunAtomically(eq(run.getId()), eq("failed"), any(), any(),
                isNull(), org.mockito.ArgumentMatchers.contains("VARIABLE_RESTORE_FAILED"), any());
        verifyNoInteractions(registry);
        assertThat(source.getOutputJson()).isEqualTo(json); // history remains untouched
        verify(nodes, never()).save(any());
    }

    @ParameterizedTest(name = "pre-created store must not bypass recovery: {0}")
    @MethodSource("unprovableOutputs")
    void precreatedStoreDoesNotBypassRecovery(String scenario, String json, String outputSha, String previewSha) {
        WorkflowNodeRun source = source(json, outputSha, previewSha);
        when(nodes.findByRunId(run.getId())).thenReturn(List.of(source));
        when(versions.findDefinition(1L)).thenReturn(Optional.of(DEFINITION))
                .thenThrow(new AssertionError("unprovable output passed the recovery boundary: " + scenario));
        VariableStore reference = engine.variableStore(run.getId());

        assertThatCode(() -> engine.drive(run)).doesNotThrowAnyException();

        verify(runs).finishRunAtomically(eq(run.getId()), eq("failed"), any(), any(),
                isNull(), org.mockito.ArgumentMatchers.contains("VARIABLE_RESTORE_FAILED"), any());
        verifyNoInteractions(registry);
        assertThat(reference.nodeOutput("source")).isNull();
        assertThat(source.getOutputJson()).isEqualTo(json);
        verify(nodes, never()).save(any());
    }

    @Test
    void completeOutputWithMatchingDigestCanBeRestored() throws Exception {
        String json = "{\"value\":1}";
        when(nodes.findByRunId(run.getId())).thenReturn(List.of(source(json, sha(json), null)));

        engine.initVariablesIfAbsent(run);

        assertThat(engine.variableStore(run.getId()).nodeOutput("source").value())
                .isEqualTo(java.util.Map.of("value", 1));
    }

    @Test
    void precreatedReferenceObservesSuccessfulRecovery() throws Exception {
        String json = "{\"value\":1}";
        when(nodes.findByRunId(run.getId())).thenReturn(List.of(source(json, sha(json), null)));
        VariableStore reference = engine.variableStore(run.getId());

        engine.initVariablesIfAbsent(run);

        assertThat(engine.variableStore(run.getId())).isSameAs(reference);
        assertThat(reference.nodeOutput("source")).isNotNull();
        assertThat(reference.nodeOutput("source").value()).isEqualTo(java.util.Map.of("value", 1));
    }

    @Test
    void laterInvalidOutputDoesNotPublishEarlierCandidateData() throws Exception {
        String json = "{\"value\":1}";
        WorkflowNodeRun invalid = source("{}", null, null);
        invalid.setNodeKey("effect");
        when(nodes.findByRunId(run.getId())).thenReturn(List.of(source(json, sha(json), null), invalid));
        run.setInputJson("{\"secret\":42}");
        VariableStore reference = engine.variableStore(run.getId());

        assertThatThrownBy(() -> engine.initVariablesIfAbsent(run)).isInstanceOf(NodeFailedException.class);
        assertThat(reference.nodeOutput("source")).isNull();
        assertThat(reference.resolve("run.input.secret")).isEmpty();
        assertThat(reference.isInitialized()).isFalse();
    }

    @Test
    void initializationWaitsForCommitAndRollbackDoesNotPublish() throws Exception {
        String json = "{\"value\":1}";
        when(nodes.findByRunId(run.getId())).thenReturn(List.of(source(json, sha(json), null)));
        VariableStore reference = engine.variableStore(run.getId());
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            engine.initVariables(run, null, null);
            assertThat(reference.nodeOutput("source")).isNull();
            var callbacks = TransactionSynchronizationManager.getSynchronizations();
            callbacks.forEach(callback -> callback.afterCompletion(
                    org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK));
            assertThat(reference.isInitialized()).isFalse();
        } finally {
            TransactionSynchronizationManager.clear();
        }

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            engine.initVariables(run, null, null);
            assertThat(reference.nodeOutput("source")).isNull();
            TransactionSynchronizationManager.getSynchronizations().forEach(callback -> callback.afterCommit());
            assertThat(reference.nodeOutput("source").value()).isEqualTo(java.util.Map.of("value", 1));
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    void concurrentRecoveryPublishesOnlyAfterCompleteValidation() throws Exception {
        String json = "{\"value\":1}";
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(nodes.findByRunId(run.getId())).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("timeout");
            return List.of(source(json, sha(json), null));
        });
        VariableStore reference = engine.variableStore(run.getId());
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> engine.initVariablesIfAbsent(run));
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var second = workers.submit(() -> engine.initVariablesIfAbsent(run));
            assertThat(reference.nodeOutput("source")).isNull();
            release.countDown();
            first.get(5, java.util.concurrent.TimeUnit.SECONDS);
            second.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(reference.nodeOutput("source").value()).isEqualTo(java.util.Map.of("value", 1));
            verify(nodes, times(1)).findByRunId(run.getId());
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    private WorkflowNodeRun source(String json, String outputSha, String previewSha) {
        WorkflowNodeRun row = new WorkflowNodeRun();
        row.setId("source-attempt");
        row.setRunId(run.getId());
        row.setNodeKey("source");
        row.setNodeType("transform");
        row.setAttempt(1);
        row.setStatus("succeeded");
        row.setOutputJson(json);
        row.setOutputSha256(outputSha);
        row.setPreviewSha256(previewSha);
        return row;
    }

    private static String sha(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
