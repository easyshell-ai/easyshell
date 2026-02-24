package com.easyshell.server.ai.orchestrator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("OrchestratorEngine — filterToolsByPermissions")
class PermissionFilterTest {

    private ToolCallback[] invokeFilter(OrchestratorEngine engine, ToolCallback[] callbacks, String permissionsJson)
            throws Exception {
        Method method = OrchestratorEngine.class.getDeclaredMethod(
                "filterToolsByPermissions", ToolCallback[].class, String.class);
        method.setAccessible(true);
        return (ToolCallback[]) method.invoke(engine, callbacks, permissionsJson);
    }

    private OrchestratorEngine createMinimalEngine() {
        return new OrchestratorEngine(
                null, null, null, null,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null
        );
    }

    private ToolCallback mockTool(String name) {
        ToolCallback cb = mock(ToolCallback.class);
        ToolDefinition td = mock(ToolDefinition.class);
        when(td.name()).thenReturn(name);
        when(cb.getToolDefinition()).thenReturn(td);
        return cb;
    }

    @Test
    void wildcardAllow_returnsAll() throws Exception {
        OrchestratorEngine engine = createMinimalEngine();
        ToolCallback[] all = {mockTool("listHosts"), mockTool("executeScript"), mockTool("monitoring")};

        String perms = "[{\"tool\":\"*\",\"action\":\"allow\"}]";
        ToolCallback[] result = invokeFilter(engine, all, perms);

        assertThat(result).hasSize(3);
    }

    @Test
    void wildcardAllowWithSpecificDeny() throws Exception {
        OrchestratorEngine engine = createMinimalEngine();
        ToolCallback[] all = {mockTool("listHosts"), mockTool("executeScript"), mockTool("monitoring")};

        String perms = "[{\"tool\":\"*\",\"action\":\"allow\"},{\"tool\":\"executeScript\",\"action\":\"deny\"}]";
        ToolCallback[] result = invokeFilter(engine, all, perms);

        assertThat(result).hasSize(2);
        assertThat(result[0].getToolDefinition().name()).isEqualTo("listHosts");
        assertThat(result[1].getToolDefinition().name()).isEqualTo("monitoring");
    }

    @Test
    void specificAllowOnly() throws Exception {
        OrchestratorEngine engine = createMinimalEngine();
        ToolCallback[] all = {mockTool("listHosts"), mockTool("executeScript"), mockTool("monitoring")};

        String perms = "[{\"tool\":\"listHosts\",\"action\":\"allow\"},{\"tool\":\"monitoring\",\"action\":\"allow\"}]";
        ToolCallback[] result = invokeFilter(engine, all, perms);

        assertThat(result).hasSize(2);
        assertThat(result[0].getToolDefinition().name()).isEqualTo("listHosts");
        assertThat(result[1].getToolDefinition().name()).isEqualTo("monitoring");
    }

    @Test
    void nullPermissions_returnsAll() throws Exception {
        OrchestratorEngine engine = createMinimalEngine();
        ToolCallback[] all = {mockTool("listHosts"), mockTool("executeScript")};

        ToolCallback[] result = invokeFilter(engine, all, null);
        assertThat(result).hasSize(2);
    }

    @Test
    void blankPermissions_returnsAll() throws Exception {
        OrchestratorEngine engine = createMinimalEngine();
        ToolCallback[] all = {mockTool("listHosts"), mockTool("executeScript")};

        ToolCallback[] result = invokeFilter(engine, all, "  ");
        assertThat(result).hasSize(2);
    }

    @Test
    void invalidJson_returnsAll() throws Exception {
        OrchestratorEngine engine = createMinimalEngine();
        ToolCallback[] all = {mockTool("listHosts"), mockTool("executeScript")};

        ToolCallback[] result = invokeFilter(engine, all, "{bad json");
        assertThat(result).hasSize(2);
    }

    @Test
    void emptyArrayPermissions_returnsNone() throws Exception {
        OrchestratorEngine engine = createMinimalEngine();
        ToolCallback[] all = {mockTool("listHosts"), mockTool("executeScript")};

        ToolCallback[] result = invokeFilter(engine, all, "[]");
        assertThat(result).isEmpty();
    }

    @Test
    void denyOnly_blocksSpecified() throws Exception {
        OrchestratorEngine engine = createMinimalEngine();
        ToolCallback[] all = {mockTool("listHosts"), mockTool("executeScript")};

        String perms = "[{\"tool\":\"executeScript\",\"action\":\"deny\"}]";
        ToolCallback[] result = invokeFilter(engine, all, perms);

        assertThat(result).isEmpty();
    }
}
