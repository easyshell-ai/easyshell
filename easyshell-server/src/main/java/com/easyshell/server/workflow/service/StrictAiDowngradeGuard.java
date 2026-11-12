package com.easyshell.server.workflow.service;

import com.easyshell.server.workflow.model.WorkflowDefinition;
import com.easyshell.server.workflow.model.WorkflowNode;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Pure comparison against a persisted snapshot; this class does not provide transaction isolation. */
public final class StrictAiDowngradeGuard {
    private static final String STRICT = "json_schema_v1";
    private static final ObjectMapper SCHEMA_MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private StrictAiDowngradeGuard() { }

    /**
     * Reject removal, key/type/mode changes and any schema change for strict AI nodes.
     * Compare separately against every applicable persisted baseline, including the current
     * published definition. Call within the same transaction and concurrency boundary as
     * the write; callers must not substitute a client-supplied baseline. A missing baseline
     * must be established under that boundary, not inferred from a failed read.
     *
     * @throws IllegalArgumentException if a protected node changes or either graph is ambiguous
     */
    public static void requirePreserved(WorkflowDefinition baseline, WorkflowDefinition candidate) {
        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(candidate, "candidate");
        Map<String, WorkflowNode> before = index(baseline);
        Map<String, WorkflowNode> after = index(candidate);
        for (WorkflowNode node : before.values()) {
            if (!isStrictAi(node)) {
                continue;
            }
            WorkflowNode replacement = after.get(node.getKey());
            if (replacement == null || !Objects.equals(node.getType(), replacement.getType())
                    || !isStrictAi(replacement)
                    || !schema(node).equals(schema(replacement))) {
                throw new IllegalArgumentException("STRICT_AI_CHANGE_FORBIDDEN: " + node.getKey());
            }
        }
    }

    private static Map<String, WorkflowNode> index(WorkflowDefinition definition) {
        if (definition.getNodes() == null) {
            throw new IllegalArgumentException("STRICT_AI_INVALID_GRAPH");
        }
        Map<String, WorkflowNode> nodes = new HashMap<>();
        for (WorkflowNode node : definition.getNodes()) {
            if (node == null || node.getKey() == null || node.getKey().isBlank()
                    || nodes.putIfAbsent(node.getKey(), node) != null) {
                throw new IllegalArgumentException("STRICT_AI_INVALID_GRAPH");
            }
        }
        return nodes;
    }

    private static boolean isStrictAi(WorkflowNode node) {
        return ("llm".equals(node.getType()) || "ai-analyze".equals(node.getType()))
                && node.getConfig() != null && STRICT.equals(node.getConfig().get("outputMode"));
    }

    private static JsonNode schema(WorkflowNode node) {
        Object raw = node.getConfig().get("jsonSchema");
        if (!(raw instanceof String text)) {
            throw new IllegalArgumentException("STRICT_AI_INVALID_SCHEMA: " + node.getKey());
        }
        try {
            JsonNode value = SCHEMA_MAPPER.readTree(text);
            if (value == null || !value.isObject()) {
                throw new IllegalArgumentException("STRICT_AI_INVALID_SCHEMA: " + node.getKey());
            }
            return value;
        } catch (IOException ex) {
            throw new IllegalArgumentException("STRICT_AI_INVALID_SCHEMA: " + node.getKey(), ex);
        }
    }
}
