package com.easyshell.server.workflow.service;

import com.easyshell.server.ai.security.SensitiveDataFilter;
import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.workflow.code.WorkflowCodeWorkerClient;
import com.easyshell.server.workflow.engine.NodeFailedException;
import com.easyshell.server.workflow.model.WorkflowNode;
import com.easyshell.server.workflow.node.CodeNodeExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Service boundary only; executor filtering and remote execution have separate tests. */
class WorkflowCodeSampleRemoteServiceTest {
    private CodeNodeExecutor executor;
    private WorkflowCodeWorkerClient worker;
    private WorkflowCodeSampleService service;

    @BeforeEach
    void setUp() {
        executor = mock(CodeNodeExecutor.class);
        worker = mock(WorkflowCodeWorkerClient.class);
        when(worker.isAvailable()).thenReturn(true);
        service = new WorkflowCodeSampleService(executor, new ObjectMapper(), new SensitiveDataFilter());
        service.setWorker(worker);
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript", "python"})
    void validRequestDelegatesParsedInputAndConfigToExecutor(String language) {
        Map<String, Object> input = Map.of("count", 7, "nested", List.of(Map.of("value", true)));
        Object output = Map.of("answer", 8);
        when(executor.executeSample(any(WorkflowNode.class), eq(input))).thenReturn(output);

        Object actual = service.execute(stream("{\"language\":\"" + language
                + "\",\"code\":\"remote source\",\"input\":{\"count\":7,\"nested\":[{\"value\":true}]},\"timeoutSec\":1}"));

        assertThat(actual).isSameAs(output);
        ArgumentCaptor<WorkflowNode> node = ArgumentCaptor.forClass(WorkflowNode.class);
        verify(executor).executeSample(node.capture(), eq(input));
        assertThat(node.getValue().getType()).isEqualTo("code");
        assertThat(node.getValue().getConfig()).containsExactlyInAnyOrderEntriesOf(
                Map.of("language", language, "code", "remote source", "timeoutSec", 1));
        verify(worker).isAvailable();
        verifyNoMoreInteractions(worker, executor);
    }

    @Test
    void acceptsExactBodyByteLimitAndDefaultsTimeoutToFiveSeconds() {
        String empty = request("\"\"");
        String text = "x".repeat(65_536 - empty.getBytes(StandardCharsets.UTF_8).length);
        String body = request("\"" + text + "\"");
        assertThat(body.getBytes(StandardCharsets.UTF_8)).hasSize(65_536);
        when(executor.executeSample(any(), anyMap())).thenReturn(Map.of("ok", true));

        assertThat(service.execute(stream(body))).isEqualTo(Map.of("ok", true));

        ArgumentCaptor<WorkflowNode> node = ArgumentCaptor.forClass(WorkflowNode.class);
        verify(executor).executeSample(node.capture(), eq(Map.of("value", text)));
        assertThat(node.getValue().configInt("timeoutSec", -1)).isEqualTo(5);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"language\":\"python\",\"code\":\"x\",\"input\":{},\"extra\":1}",
            "{\"language\":\"python\",\"language\":\"javascript\",\"code\":\"x\",\"input\":{}}",
            "{\"language\":\"python\",\"code\":\"x\",\"input\":{\"a\":1,\"a\":2}}",
            "{\"language\":\"python\",\"code\":\"x\",\"input\":{}} {}",
            "{\"language\":\"python\",\"code\":\"x\",\"input\":{},\"timeoutSec\":1.5}",
            "{\"language\":\"python\",\"code\":\"x\",\"input\":{},\"timeoutSec\":1.0}",
            "{\"language\":\"python\",\"code\":\"x\",\"input\":{},\"timeoutSec\":2147483648}",
            "{\"language\":\"python\",\"code\":\"x\",\"input\":{},\"timeoutSec\":\"1\"}",
            "{\"language\":\"python\",\"code\":\"x\",\"input\":[]}",
            "{\"language\":\"python\",\"input\":{}}", "[]", "null", "{", ""
    })
    void malformedUnknownDuplicateTrailingOrInvalidFieldTypesNeverReachExecutor(String body) {
        assertError(stream(body), 400, "CODE_SAMPLE_INVALID");
        verifyNoInteractions(executor);
    }

    @Test
    void excessiveJsonDepthIsRejectedByActualMapperConstraints() {
        // Use the production mapper defaults, rather than tightening only the test mapper.
        int depth = new ObjectMapper().getFactory().streamReadConstraints().getMaxNestingDepth() + 1;
        String body = request("[".repeat(depth) + "0" + "]".repeat(depth));
        assertThat(body.getBytes(StandardCharsets.UTF_8).length).isLessThan(65_536);

        assertError(stream(body), 400, "CODE_SAMPLE_INVALID");

        verifyNoInteractions(executor);
    }

    @Test
    void oversizedBodyReadsOnlyLimitPlusOneAndNeverReachesExecutor() {
        AtomicInteger read = new AtomicInteger();
        InputStream endless = new InputStream() {
            @Override public int read() { read.incrementAndGet(); return 'x'; }
            @Override public int read(byte[] bytes, int offset, int length) {
                java.util.Arrays.fill(bytes, offset, offset + length, (byte) 'x');
                read.addAndGet(length);
                return length;
            }
        };

        assertError(endless, 413, "CODE_SAMPLE_INPUT_TOO_LARGE");

        assertThat(read.get()).isEqualTo(65_537);
        verifyNoInteractions(executor);
    }

    @Test
    void multibyteBodyIsLimitedByUtf8Bytes() {
        String body = request("\"" + "界".repeat(22_000) + "\"");
        assertThat(body.length()).isLessThan(65_536);
        assertThat(body.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(65_536);

        assertError(stream(body), 413, "CODE_SAMPLE_INPUT_TOO_LARGE");

        verifyNoInteractions(executor);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CODE_RUNTIME_UNAVAILABLE", "CODE_EXECUTION_UNCERTAIN", "NODE_CONFIG_INVALID", "TIMEOUT"})
    void executorFailureMapsToFixedStatusAndCodeWithoutExceptionDetails(String code) {
        when(executor.executeSample(any(), anyMap())).thenThrow(
                new NodeFailedException(code, "secret source and input must not escape"));

        assertError(stream(request("1")), "CODE_RUNTIME_UNAVAILABLE".equals(code) ? 503 : 422, code);

        verify(executor, times(1)).executeSample(any(), eq(Map.of("value", 1)));
    }

    @Test
    void disabledWorkerRejectsBeforeReading() {
        when(worker.isAvailable()).thenReturn(false);
        InputStream unreadable = new InputStream() {
            @Override public int read() { throw new AssertionError("Disabled service read body"); }
            @Override public byte[] readNBytes(int length) { throw new AssertionError("Disabled service read body"); }
        };

        assertError(unreadable, 503, "CODE_SAMPLE_RESOURCE_ISOLATION_UNAVAILABLE");

        verify(worker).isAvailable();
        verifyNoMoreInteractions(worker);
        verifyNoInteractions(executor);
    }

    private void assertError(InputStream body, int status, String code) {
        assertThatThrownBy(() -> service.execute(body)).isInstanceOfSatisfying(BusinessException.class, error -> {
            assertThat(error.getCode()).isEqualTo(status);
            assertThat(error.getMessage()).isEqualTo(code);
        });
    }

    private static String request(String value) {
        return "{\"language\":\"python\",\"code\":\"remote source\",\"input\":{\"value\":" + value + "}}";
    }

    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
