package com.easyshell.server.workflow.service;

import com.easyshell.server.ai.security.SensitiveDataFilter;
import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.workflow.node.CodeNodeExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class WorkflowCodeSampleServiceTest {
    private final CodeNodeExecutor executor = mock(CodeNodeExecutor.class);
    private final ObjectMapper mapper = mock(ObjectMapper.class);
    private final SensitiveDataFilter filter = mock(SensitiveDataFilter.class);
    private final WorkflowCodeSampleService service = new WorkflowCodeSampleService(executor, mapper, filter);

    @Test
    void rejectsValidAndMaliciousSamplesWithoutInvokingSandbox() {
        assertUnavailable(stream("{\"language\":\"javascript\",\"code\":\"function main(input) { return input; }\",\"input\":{}}"));
        assertUnavailable(stream("{\"language\":\"javascript\",\"code\":\"function main(input) { while (true) { new Array(1000000); } }\",\"input\":{}}"));
        verifyNoInteractions(executor, mapper, filter);
    }

    @Test
    void rejectsBeforeReadingOrParsingAnyBody() {
        InputStream unreadable = new InputStream() {
            @Override
            public int read() {
                throw new AssertionError("request body must not be read");
            }
        };
        assertUnavailable(unreadable);
        assertUnavailable(stream("not json"));
        assertUnavailable(stream("x".repeat(1_100_001)));
        verifyNoInteractions(executor, mapper, filter);
    }

    private void assertUnavailable(InputStream body) {
        assertThatThrownBy(() -> service.execute(body))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(503);
                    assertThat(e.getMessage()).isEqualTo("CODE_SAMPLE_RESOURCE_ISOLATION_UNAVAILABLE");
                });
    }

    private InputStream stream(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
