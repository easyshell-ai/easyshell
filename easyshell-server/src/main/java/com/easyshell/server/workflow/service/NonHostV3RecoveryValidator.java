package com.easyshell.server.workflow.service;

import com.easyshell.server.model.entity.WorkflowApproval;
import com.easyshell.server.model.entity.WorkflowNodeRun;
import com.easyshell.server.workflow.model.WorkflowDefinition;
import com.easyshell.server.workflow.model.WorkflowEdge;
import com.easyshell.server.workflow.model.WorkflowNode;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

/** Unwired, fail-closed snapshot check. This is not a substitute for transactional locking. */
public final class NonHostV3RecoveryValidator {
    private static final Set<String> NODE_STATUSES = Set.of("pending", "running", "waiting", "succeeded", "failed", "skipped", "cancelled", "timeout");
    private static final Set<String> APPROVAL_STATUSES = Set.of("pending", "approved", "rejected", "timeout", "cancelled");
    private final ObjectMapper mapper;

    public NonHostV3RecoveryValidator(ObjectMapper mapper) {
        this.mapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    /** Callers must supply a consistent, complete snapshot for exactly runId. No state is changed. */
    public boolean allows(String runId, String definitionJson, List<WorkflowNodeRun> attempts,
                          List<WorkflowApproval> approvals) {
        try {
            if (runId == null || runId.isBlank() || attempts == null || approvals == null) return false;
            WorkflowDefinition def = mapper.readValue(definitionJson, WorkflowDefinition.class);
            if (def == null || def.getNodes() == null || def.getNodes().isEmpty() || def.getEdges() == null) return false;
            Map<String, WorkflowNode> nodes = new HashMap<>();
            Map<String, List<String>> outgoing = new HashMap<>();
            Map<String, Integer> indegree = new HashMap<>();
            for (WorkflowNode node : def.getNodes()) {
                if (node == null || node.getKey() == null || node.getKey().isBlank() || node.getType() == null
                        || nodes.putIfAbsent(node.getKey(), node) != null) return false;
                outgoing.put(node.getKey(), new ArrayList<>());
                indegree.put(node.getKey(), 0);
            }
            for (WorkflowEdge edge : def.getEdges()) {
                if (edge == null || !nodes.containsKey(edge.getFrom()) || !nodes.containsKey(edge.getTo())) return false;
                outgoing.get(edge.getFrom()).add(edge.getTo());
                indegree.compute(edge.getTo(), (k, n) -> n + 1);
            }
            Queue<String> queue = new ArrayDeque<>();
            indegree.forEach((key, degree) -> { if (degree == 0) queue.add(key); });
            int visited = 0;
            while (!queue.isEmpty()) {
                String key = queue.remove();
                visited++;
                for (String next : outgoing.get(key)) if (indegree.compute(next, (k, n) -> n - 1) == 0) queue.add(next);
            }
            if (visited != nodes.size()) return false;

            Set<String> downstream = new HashSet<>();
            for (WorkflowNode node : nodes.values()) if ("approval".equals(node.getType())) queue.add(node.getKey());
            if (queue.isEmpty()) return false;
            while (!queue.isEmpty()) {
                for (String next : outgoing.get(queue.remove())) if (downstream.add(next)) queue.add(next);
            }

            Map<Long, WorkflowApproval> byId = new HashMap<>();
            for (WorkflowApproval approval : approvals) {
                if (approval == null || approval.getId() == null || !runId.equals(approval.getRunId())
                        || !"approval".equals(approval.getNodeType()) || !nodes.containsKey(approval.getNodeKey())
                        || !"approval".equals(nodes.get(approval.getNodeKey()).getType())
                        || approval.getStatus() == null || !APPROVAL_STATUSES.contains(approval.getStatus())
                        || byId.putIfAbsent(approval.getId(), approval) != null) return false;
            }
            Set<Long> bound = new HashSet<>();
            Set<String> attemptKeys = new HashSet<>();
            for (WorkflowNodeRun attempt : attempts) {
                if (attempt == null || !runId.equals(attempt.getRunId()) || !nodes.containsKey(attempt.getNodeKey())
                        || !Objects.equals(nodes.get(attempt.getNodeKey()).getType(), attempt.getNodeType())
                        || attempt.getAttempt() == null || attempt.getAttempt() < 1
                        || !attemptKeys.add(attempt.getNodeKey() + "\u0000" + attempt.getAttempt())
                        || attempt.getStatus() == null || !NODE_STATUSES.contains(attempt.getStatus())
                        || downstream.contains(attempt.getNodeKey()) || !validJson(attempt.getInputJson())
                        || !validJson(attempt.getOutputJson())) return false;
                Long id = attempt.getApprovalId();
                if (id != null) {
                    WorkflowApproval approval = byId.get(id);
                    if (!"approval".equals(attempt.getNodeType()) || approval == null || !bound.add(id)
                            || !attempt.getNodeKey().equals(approval.getNodeKey())) return false;
                }
                if ("approval".equals(attempt.getNodeType())) {
                    // These persisted strings can be replayed or exposed; valid JSON alone does not
                    // establish their provenance. Only the exact successful output is admissible.
                    if (attempt.getInputJson() != null || attempt.getErrorMessage() != null
                            || attempt.getWarningMessage() != null) return false;
                    switch (attempt.getStatus()) {
                        case "pending" -> {
                            if (id != null || attempt.getOutputJson() != null) return false;
                        }
                        case "running" -> {
                            // Initial execution has no binding; resumed execution has one.
                            if (attempt.getOutputJson() != null) return false;
                        }
                        case "waiting" -> {
                            // The approval may already be decided before the engine resumes it.
                            if (id == null || attempt.getOutputJson() != null) return false;
                        }
                        case "succeeded" -> {
                            if (id == null || !matches(attempt.getOutputJson(), byId.get(id))) return false;
                        }
                        default -> { return false; }
                    }
                }
            }
            return bound.size() == byId.size();
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean validJson(String json) throws Exception {
        return json == null || mapper.readTree(json) != null;
    }

    private boolean matches(String json, WorkflowApproval approval) throws Exception {
        if (json == null || approval == null || !Set.of("approved", "timeout").contains(approval.getStatus())) return false;
        JsonNode output = mapper.readTree(json);
        if (output == null || !output.isObject() || output.size() != 2
                || !output.has("decision") || !output.has("decidedBy")
                || !output.get("decision").isTextual() || !approval.getStatus().equals(output.get("decision").textValue())) return false;
        JsonNode decidedBy = output.get("decidedBy");
        return approval.getDecidedBy() == null ? decidedBy.isNull()
                : decidedBy.isIntegralNumber() && decidedBy.canConvertToLong() && decidedBy.longValue() == approval.getDecidedBy();
    }
}
