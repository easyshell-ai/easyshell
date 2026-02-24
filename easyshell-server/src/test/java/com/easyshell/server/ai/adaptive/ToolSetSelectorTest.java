package com.easyshell.server.ai.adaptive;

import com.easyshell.server.ai.config.AgenticConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ToolSetSelector — task-based tool filtering")
class ToolSetSelectorTest {

    @Mock
    private AgenticConfigService configService;

    private ToolSetSelector selector;

    @BeforeEach
    void setUp() {
        selector = new ToolSetSelector(configService);
    }

    private ToolCallback mockTool(String name) {
        ToolCallback cb = mock(ToolCallback.class);
        ToolDefinition td = mock(ToolDefinition.class);
        lenient().when(td.name()).thenReturn(name);
        lenient().when(cb.getToolDefinition()).thenReturn(td);
        return cb;
    }

    private ToolCallback[] allTools() {
        return new ToolCallback[]{
                mockTool("hostList"),
                mockTool("executeScript"),
                mockTool("monitorOverview"),
                mockTool("taskList"),
                mockTool("customTool")
        };
    }

    @Test
    void disabledAdaptive_returnsAllTools() {
        when(configService.getBoolean(eq("ai.adaptive.enabled"), anyBoolean())).thenReturn(false);
        ToolCallback[] result = selector.selectTools(TaskType.QUERY, allTools());
        assertThat(result).hasSize(5);
    }

    @Test
    void executeTask_returnsAllTools() {
        when(configService.getBoolean(eq("ai.adaptive.enabled"), anyBoolean())).thenReturn(true);
        ToolCallback[] result = selector.selectTools(TaskType.EXECUTE, allTools());
        assertThat(result).hasSize(5);
    }

    @Test
    void troubleshootTask_returnsAllTools() {
        when(configService.getBoolean(eq("ai.adaptive.enabled"), anyBoolean())).thenReturn(true);
        ToolCallback[] result = selector.selectTools(TaskType.TROUBLESHOOT, allTools());
        assertThat(result).hasSize(5);
    }

    @Test
    void deployTask_returnsAllTools() {
        when(configService.getBoolean(eq("ai.adaptive.enabled"), anyBoolean())).thenReturn(true);
        ToolCallback[] result = selector.selectTools(TaskType.DEPLOY, allTools());
        assertThat(result).hasSize(5);
    }

    @Test
    void queryTask_filtersToWhitelist() {
        when(configService.getBoolean(eq("ai.adaptive.enabled"), anyBoolean())).thenReturn(true);
        ToolCallback[] result = selector.selectTools(TaskType.QUERY, allTools());
        assertThat(result.length).isLessThan(5);
        for (ToolCallback cb : result) {
            assertThat(cb.getToolDefinition().name()).isNotEqualTo("executeScript");
            assertThat(cb.getToolDefinition().name()).isNotEqualTo("customTool");
        }
    }

    @Test
    void generalTask_filtersToWhitelist() {
        when(configService.getBoolean(eq("ai.adaptive.enabled"), anyBoolean())).thenReturn(true);
        ToolCallback[] result = selector.selectTools(TaskType.GENERAL, allTools());
        assertThat(result.length).isLessThan(5);
    }

    @Test
    void monitorTask_filtersToMonitorWhitelist() {
        when(configService.getBoolean(eq("ai.adaptive.enabled"), anyBoolean())).thenReturn(true);
        ToolCallback[] result = selector.selectTools(TaskType.MONITOR, allTools());
        assertThat(result.length).isLessThan(5);
    }
}
