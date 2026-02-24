package com.easyshell.server.ai.controller;

import com.easyshell.server.ai.model.entity.AiSessionSummary;
import com.easyshell.server.ai.repository.AiSessionSummaryRepository;
import com.easyshell.server.common.result.R;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MemoryController — Memory CRUD API")
class MemoryControllerTest {

    @Mock
    private AiSessionSummaryRepository sessionSummaryRepository;

    private MemoryController controller;

    @BeforeEach
    void setUp() {
        controller = new MemoryController(sessionSummaryRepository);
    }

    @Test
    void getById_found() {
        AiSessionSummary summary = new AiSessionSummary();
        summary.setId(1L);
        summary.setSessionId("session-abc");
        summary.setSummary("test summary");
        when(sessionSummaryRepository.findById(1L)).thenReturn(Optional.of(summary));

        R<AiSessionSummary> result = controller.getById(1L);
        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData().getSummary()).isEqualTo("test summary");
    }

    @Test
    void getById_notFound() {
        when(sessionSummaryRepository.findById(99L)).thenReturn(Optional.empty());
        R<AiSessionSummary> result = controller.getById(99L);
        assertThat(result.getCode()).isNotEqualTo(200);
    }

    @Test
    void delete_existingMemory() {
        when(sessionSummaryRepository.existsById(1L)).thenReturn(true);
        R<Void> result = controller.delete(1L);
        assertThat(result.getCode()).isEqualTo(200);
        verify(sessionSummaryRepository).deleteById(1L);
    }

    @Test
    void delete_notFound() {
        when(sessionSummaryRepository.existsById(99L)).thenReturn(false);
        R<Void> result = controller.delete(99L);
        assertThat(result.getCode()).isNotEqualTo(200);
    }

    @Test
    void clearAll_deletesAllRecords() {
        R<Void> result = controller.clearAll();
        assertThat(result.getCode()).isEqualTo(200);
        verify(sessionSummaryRepository).deleteAll();
    }
}
