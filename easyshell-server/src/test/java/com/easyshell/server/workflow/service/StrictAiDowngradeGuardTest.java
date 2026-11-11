package com.easyshell.server.workflow.service;

import com.easyshell.server.workflow.model.WorkflowDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StrictAiDowngradeGuardTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{\"ok\":{\"type\":\"boolean\"}},\"required\":[\"ok\"]}";

    private WorkflowDefinition definition(String nodes) throws Exception {
        return MAPPER.readValue("{\"nodes\":[" + nodes + "]}", WorkflowDefinition.class);
    }

    private String node(String key, String type, String mode, String schema) throws Exception {
        return "{\"key\":" + MAPPER.writeValueAsString(key) + ",\"type\":" + MAPPER.writeValueAsString(type)
                + ",\"config\":{\"outputMode\":" + MAPPER.writeValueAsString(mode)
                + ",\"jsonSchema\":" + MAPPER.writeValueAsString(schema) + "}}";
    }

    @ParameterizedTest
    @ValueSource(strings = {"llm", "ai-analyze"})
    void rejectsRemovalRenameTypeModeAndSchemaChanges(String type) throws Exception {
        WorkflowDefinition baseline = definition(node("ai", type, "json_schema_v1", SCHEMA));
        for (String replacement : new String[] {
                "", node("renamed", type, "json_schema_v1", SCHEMA),
                node("ai", "wait", "json_schema_v1", SCHEMA),
                node("ai", type.equals("llm") ? "ai-analyze" : "llm", "json_schema_v1", SCHEMA),
                node("ai", type, "text", SCHEMA), node("ai", type, "json", SCHEMA),
                node("ai", type, "json_schema_v1", "{\"type\":\"object\"}")
        }) {
            WorkflowDefinition candidate = definition(replacement);
            assertThrows(IllegalArgumentException.class,
                    () -> StrictAiDowngradeGuard.requirePreserved(baseline, candidate));
        }
    }

    @Test
    void schemaObjectOrderingAndWhitespaceDoNotChangeConstraints() throws Exception {
        WorkflowDefinition before = definition(node("ai", "llm", "json_schema_v1", SCHEMA));
        WorkflowDefinition after = definition(node("ai", "llm", "json_schema_v1",
                "{ \"required\": [\"ok\"], \"properties\": {\"ok\": {\"type\": \"boolean\"}}, \"type\": \"object\" }"));
        assertDoesNotThrow(() -> StrictAiDowngradeGuard.requirePreserved(before, after));
    }

    @Test
    void legacyJsonIsNotStrictAndCanBeEditedOrRemoved() throws Exception {
        WorkflowDefinition before = definition(node("ai", "llm", "json", SCHEMA));
        assertDoesNotThrow(() -> StrictAiDowngradeGuard.requirePreserved(before, definition("")));
    }

    @Test
    void overwriteMustCompareToCurrentPersistedDraftNotStaleClientSnapshot() throws Exception {
        WorkflowDefinition stale = definition(node("ai", "llm", "json", SCHEMA));
        WorkflowDefinition current = definition(node("ai", "llm", "json_schema_v1", SCHEMA));
        assertDoesNotThrow(() -> StrictAiDowngradeGuard.requirePreserved(stale, stale));
        assertThrows(IllegalArgumentException.class, () -> StrictAiDowngradeGuard.requirePreserved(current, stale));
    }

    @Test
    void rejectsAmbiguousNodesAndInvalidSchema() throws Exception {
        String strict = node("ai", "llm", "json_schema_v1", SCHEMA);
        WorkflowDefinition baseline = definition(strict);
        WorkflowDefinition duplicate = definition(strict + "," + strict);
        WorkflowDefinition invalid = definition(node("ai", "llm", "json_schema_v1", "{\"type\":\"object\",\"type\":\"object\"}"));
        assertThrows(IllegalArgumentException.class, () -> StrictAiDowngradeGuard.requirePreserved(baseline, duplicate));
        assertThrows(IllegalArgumentException.class, () -> StrictAiDowngradeGuard.requirePreserved(baseline, invalid));
    }
}
