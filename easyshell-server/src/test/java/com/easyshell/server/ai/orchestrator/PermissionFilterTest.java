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
        // 36 constructor arguments, all unused by filterToolsByPermissions
        Object[] args = new Object[36];
        return (OrchestratorEngine) createInstance(args);
    }

    private static Object createInstance(Object[] args) {
        try {
            Class<?>[] types = new Class<?>[args.length];
            java.util.Arrays.fill(types, Object.class);
            var ctor = OrchestratorEngine.class.getConstructors()[0];
            for (var c : OrchestratorEngine.class.getConstructors()) {
                if (c.getParameterCount() == args.length) {
                    ctor = c;
                    break;
                }
            }
            return ctor.newInstance(args);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
    void nullPermissions_returnsNone_failClosed() throws Exception {
        OrchestratorEngine engine = createMinimalEngine();
        ToolCallback[] all = {mockTool("listHosts"), mockTool("executeScript")};

        ToolCallback[] result = invokeFilter(engine, all, null);
        assertThat(result).isEmpty();
    }

    @Test
    void blankPermissions_returnsNone_failClosed() throws Exception {
        OrchestratorEngine engine = createMinimalEngine();
        ToolCallback[] all = {mockTool("listHosts"), mockTool("executeScript")};

        ToolCallback[] result = invokeFilter(engine, all, "  ");
        assertThat(result).isEmpty();
    }

    @Test
    void invalidJson_returnsNone_failClosed() throws Exception {
        OrchestratorEngine engine = createMinimalEngine();
        ToolCallback[] all = {mockTool("listHosts"), mockTool("executeScript")};

        ToolCallback[] result = invokeFilter(engine, all, "{bad json");
        assertThat(result).isEmpty();
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
