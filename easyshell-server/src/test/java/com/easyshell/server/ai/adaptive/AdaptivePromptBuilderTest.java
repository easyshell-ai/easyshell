package com.easyshell.server.ai.adaptive;

import com.easyshell.server.ai.chat.SystemPrompts;
import com.easyshell.server.ai.config.AgenticConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdaptivePromptBuilder — dynamic system prompt assembly")
class AdaptivePromptBuilderTest {

    @Mock
    private AgenticConfigService configService;

    private AdaptivePromptBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new AdaptivePromptBuilder(configService);
    }

    private void enableAdaptive() {
        when(configService.getBoolean(eq("ai.adaptive.enabled"), anyBoolean())).thenReturn(true);
        when(configService.get(eq("ai.prompt.base"), any())).thenReturn(null);
        when(configService.get(startsWith("ai.prompt.task."), any())).thenReturn(null);
    }

    @Test
    void disabledAdaptive_returnsFallbackPrompt() {
        when(configService.getBoolean(eq("ai.adaptive.enabled"), anyBoolean())).thenReturn(false);
        String result = builder.buildPrompt(TaskType.QUERY, null, null);
        assertThat(result).isEqualTo(SystemPrompts.OPS_ASSISTANT);
    }

    @Test
    void queryTask_includesQueryMode() {
        enableAdaptive();
        String result = builder.buildPrompt(TaskType.QUERY, null, null);
        assertThat(result).contains("Query Mode");
    }

    @Test
    void troubleshootTask_includesTroubleshootingMode() {
        enableAdaptive();
        String result = builder.buildPrompt(TaskType.TROUBLESHOOT, null, null);
        assertThat(result).contains("Troubleshooting Mode");
    }

    @Test
    void executeTask_includesExecutionMode() {
        enableAdaptive();
        String result = builder.buildPrompt(TaskType.EXECUTE, null, null);
        assertThat(result).contains("Execution Mode");
    }

    @Test
    void generalTask_noTaskSpecificSection() {
        enableAdaptive();
        String result = builder.buildPrompt(TaskType.GENERAL, null, null);
        assertThat(result).doesNotContain("## Query Mode");
        assertThat(result).doesNotContain("## Execution Mode");
        assertThat(result).doesNotContain("## Troubleshooting Mode");
    }

    @Test
    void withMemoryContext_appendsMemorySection() {
        enableAdaptive();
        String result = builder.buildPrompt(TaskType.GENERAL, "some memory context", null);
        assertThat(result).contains("## Relevant Historical Memory");
        assertThat(result).contains("some memory context");
    }

    @Test
    void withSopSuggestion_appendsSopSection() {
        enableAdaptive();
        String result = builder.buildPrompt(TaskType.GENERAL, null, "SOP: restart nginx");
        assertThat(result).contains("## Recommended SOP");
        assertThat(result).contains("SOP: restart nginx");
    }

    @Test
    void withBothMemoryAndSop_appendsBothSections() {
        enableAdaptive();
        String result = builder.buildPrompt(TaskType.QUERY, "memory data", "sop data");
        assertThat(result)
                .contains("## Relevant Historical Memory")
                .contains("memory data")
                .contains("## Recommended SOP")
                .contains("sop data");
    }
}
