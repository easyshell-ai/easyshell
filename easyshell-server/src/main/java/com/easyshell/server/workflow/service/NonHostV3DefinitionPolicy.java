package com.easyshell.server.workflow.service;

import com.easyshell.server.workflow.expression.ExpressionEvaluator;
import com.easyshell.server.workflow.graph.DagValidator;
import com.easyshell.server.workflow.graph.ValidationIssue;
import com.easyshell.server.workflow.model.WorkflowDefinition;
import com.easyshell.server.workflow.model.WorkflowEdge;
import com.easyshell.server.workflow.model.WorkflowNode;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Preparatory definition-only check. Not wired into provenance or execution; it is
 * neither an authorization decision nor a runtime guard. Its deliberately restricted
 * expression lexer must be reviewed whenever the evaluator grammar changes.
 */
public final class NonHostV3DefinitionPolicy {
    private static final Map<String, Set<String>> CONFIG = Map.of(
            "start", Set.of(), "wait", Set.of("durationSec"),
            "condition", Set.of("expression"), "transform", Set.of("mappings"),
            "end", Set.of("outputs"),
            "approval", Set.of("title", "detailTemplate", "parameters", "mode", "approverRole", "onTimeout", "timeoutSec", "requireComment"),
            "llm", Set.of("userPrompt", "systemPrompt", "provider", "model", "outputMode", "temperature", "maxInputChars", "jsonSchema"));
    private static final Pattern PATH = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*");
    private static final Pattern TEMPLATE = Pattern.compile("\\$\\{([^{}]*)}");
    private static final Pattern TOKEN = Pattern.compile(
            "\\s+|'(?:''|[^'])*'|-?[0-9]+(?:\\.[0-9]+)?|[A-Za-z_][A-Za-z0-9_.]*|==|!=|>=|<=|&&|\\|\\||>|<");
    private final ObjectMapper mapper;
    private final DagValidator dag;

    public NonHostV3DefinitionPolicy(ObjectMapper mapper) {
        this.mapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.dag = new DagValidator(new ExpressionEvaluator(this.mapper));
    }

    /** False also covers malformed JSON, unknown fields, unsupported syntax and validator failures. */
    public boolean allows(String json) {
        try {
            WorkflowDefinition def = mapper.readValue(json, WorkflowDefinition.class);
            if (def == null || def.getNodes() == null || def.getNodes().isEmpty() || def.getEdges() == null)
                return false;
            boolean approval = false;
            for (WorkflowNode node : def.getNodes()) {
                if (node == null || !CONFIG.containsKey(node.getType())) return false;
                approval |= "approval".equals(node.getType());
                Map<String, Object> config = node.getConfig();
                if (config != null && !CONFIG.get(node.getType()).containsAll(config.keySet())) return false;
            }
            if (!approval || dag.validate(def).stream().anyMatch(i -> i.severity() == ValidationIssue.Severity.ERROR))
                return false;
            for (WorkflowNode node : def.getNodes()) {
                Map<String, Object> c = node.getConfig() == null ? Map.of() : node.getConfig();
                switch (node.getType()) {
                    case "condition" -> { if (!expression(c.get("expression"), def)) return false; }
                    case "transform", "end" -> {
                        Object rows = c.get(node.getType().equals("end") ? "outputs" : "mappings");
                        if (rows != null && !rows(rows, def)) return false;
                    }
                    // systemPrompt/jsonSchema are passed as literal text, not rendered by LlmNodeExecutor.
                    case "llm" -> { if (!template(c.get("userPrompt"), def, true)) return false; }
                    case "approval" -> {
                        // Named slots can inject a second-stage ${...} reference at runtime.
                        Object params = c.get("parameters");
                        if (params != null && (!(params instanceof Map<?, ?> map) || !map.isEmpty())) return false;
                        if (!template(c.get("title"), def, false) || !template(c.get("detailTemplate"), def, false)) return false;
                    }
                    default -> { }
                }
            }
            for (WorkflowEdge edge : def.getEdges()) {
                if (edge == null || !expression(edge.getCondition(), def)) return false;
            }
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean rows(Object raw, WorkflowDefinition def) {
        if (!(raw instanceof java.util.List<?> list)) return false;
        for (Object row : list) {
            if (!(row instanceof Map<?, ?> map) || !Set.of("name", "value").equals(map.keySet())
                    || !(map.get("name") instanceof String) || !(map.get("value") instanceof String)
                    || !template(map.get("value"), def, false)) return false;
        }
        return true;
    }

    private boolean template(Object value, WorkflowDefinition def, boolean purePath) {
        if (value == null) return true;
        if (!(value instanceof String text) || text.contains("{{") || text.contains("}}")) return false;
        if (purePath && !text.contains("${") && PATH.matcher(text.trim()).matches() && text.trim().contains("."))
            return safePath(text.trim(), def);
        Matcher m = TEMPLATE.matcher(text);
        int end = 0;
        while (m.find()) {
            if (text.substring(end, m.start()).contains("${") || !safePath(m.group(1).trim(), def)) return false;
            end = m.end();
        }
        return !text.substring(end).contains("${");
    }

    private boolean expression(Object value, WorkflowDefinition def) {
        if (value == null) return true;
        if (!(value instanceof String text) || text.contains("[") || text.contains("]")) return false;
        Matcher tokens = TOKEN.matcher(text);
        int offset = 0;
        while (offset < text.length()) {
            tokens.region(offset, text.length());
            if (!tokens.lookingAt()) return false;
            String token = tokens.group();
            offset = tokens.end();
            char first = token.charAt(0);
            if (Character.isWhitespace(first) || first == '\'' || first == '-' || Character.isDigit(first)
                    || "=!><&|".indexOf(first) >= 0
                    || Set.of("true", "false", "null", "contains").contains(token)) continue;
            if (!safePath(token, def)) return false;
        }
        return true; // DagValidator independently validates expression grammar.
    }

    private boolean safePath(String path, WorkflowDefinition def) {
        if (!PATH.matcher(path).matches()) return false;
        String[] parts = path.split("\\.");
        // VariableStore ignores segments after the input name, so reject those rather than
        // implying nested traversal. Inputs are trusted user data, not proof of origin.
        if (parts.length == 3 && "run".equals(parts[0]) && "input".equals(parts[1])) return true;
        // Deliberately reject aliases, other node outputs, and whole-object access.
        if (parts.length != 4 || !"nodes".equals(parts[0]) || !"output".equals(parts[2])) return false;
        WorkflowNode node = def.nodeByKey(parts[1]);
        return node != null && "approval".equals(node.getType())
                && Set.of("decision", "decidedBy").contains(parts[3]);
    }
}
