package com.easyshell.server.ai.controller;

import com.easyshell.server.ai.learning.SopExtractor;
import com.easyshell.server.ai.model.entity.AiSopTemplate;
import com.easyshell.server.ai.repository.AiSopTemplateRepository;
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
@DisplayName("SopController — SOP CRUD API")
class SopControllerTest {

    @Mock
    private AiSopTemplateRepository sopTemplateRepository;
    @Mock
    private SopExtractor sopExtractor;

    private SopController controller;

    @BeforeEach
    void setUp() {
        controller = new SopController(sopTemplateRepository, sopExtractor);
    }

    @Test
    void getById_found() {
        AiSopTemplate sop = AiSopTemplate.builder().id(1L).title("test").build();
        when(sopTemplateRepository.findById(1L)).thenReturn(Optional.of(sop));

        R<AiSopTemplate> result = controller.getById(1L);
        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData().getTitle()).isEqualTo("test");
    }

    @Test
    void getById_notFound() {
        when(sopTemplateRepository.findById(99L)).thenReturn(Optional.empty());

        R<AiSopTemplate> result = controller.getById(99L);
        assertThat(result.getCode()).isNotEqualTo(200);
    }

    @Test
    void update_existingSop() {
        AiSopTemplate existing = AiSopTemplate.builder().id(1L).title("old").description("old desc").build();
        when(sopTemplateRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(sopTemplateRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        AiSopTemplate request = new AiSopTemplate();
        request.setTitle("new title");

        R<AiSopTemplate> result = controller.update(1L, request);
        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData().getTitle()).isEqualTo("new title");
    }

    @Test
    void update_notFound() {
        when(sopTemplateRepository.findById(99L)).thenReturn(Optional.empty());
        R<AiSopTemplate> result = controller.update(99L, new AiSopTemplate());
        assertThat(result.getCode()).isNotEqualTo(200);
    }

    @Test
    void delete_existingSop() {
        when(sopTemplateRepository.existsById(1L)).thenReturn(true);
        R<Void> result = controller.delete(1L);
        assertThat(result.getCode()).isEqualTo(200);
        verify(sopTemplateRepository).deleteById(1L);
    }

    @Test
    void delete_notFound() {
        when(sopTemplateRepository.existsById(99L)).thenReturn(false);
        R<Void> result = controller.delete(99L);
        assertThat(result.getCode()).isNotEqualTo(200);
    }

    @Test
    void triggerExtraction_callsSopExtractor() {
        R<String> result = controller.triggerExtraction();
        assertThat(result.getCode()).isEqualTo(200);
        verify(sopExtractor).extractSopPatterns();
    }
}
