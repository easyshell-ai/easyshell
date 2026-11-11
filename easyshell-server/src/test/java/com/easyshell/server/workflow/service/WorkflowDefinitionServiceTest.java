package com.easyshell.server.workflow.service;

import com.easyshell.server.model.entity.Script;
import com.easyshell.server.security.ScriptAccess;
import com.easyshell.server.workflow.model.WorkflowDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class WorkflowDefinitionServiceTest {
    @Test
    void freezesReversedAndRepeatedScriptReferencesInNumericLockOrder() throws Exception {
        ScriptAccess access = mock(ScriptAccess.class);
        Script script = new Script();
        script.setContent("hello");
        when(access.requireReadForSnapshot(eq(7L), anyLong())).thenReturn(script);
        WorkflowDefinitionService service = new WorkflowDefinitionService(null, null, access, null,
                null, null, null, new ObjectMapper(), mock(PlatformTransactionManager.class));
        String hash = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";
        ObjectMapper mapper = new ObjectMapper();
        for (String ids : new String[]{"10,2,10", "2,10,2"}) {
            String[] parts = ids.split(",");
            StringBuilder nodes = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) nodes.append(',');
                nodes.append("{\"key\":\"n").append(i).append("\",\"type\":\"script-exec\",\"config\":{")
                        .append("\"scriptSource\":{\"kind\":\"library-v1\",\"scriptId\":")
                        .append(parts[i]).append(",\"version\":1,\"sha256\":\"").append(hash).append("\"}}}");
            }
            WorkflowDefinition definition = mapper.readValue("{\"nodes\":[" + nodes + "],\"edges\":[]}",
                    WorkflowDefinition.class);
            ReflectionTestUtils.invokeMethod(service, "freezeScripts", definition, 7L);
            InOrder order = inOrder(access);
            order.verify(access).requireReadForSnapshot(7L, 2L);
            order.verify(access).requireReadForSnapshot(7L, 10L);
            verifyNoMoreInteractions(access);
            assertThat(definition.getNodes()).allSatisfy(node ->
                    assertThat(node.getConfig().get("script")).isEqualTo("hello"));
            clearInvocations(access);
        }
    }
}
