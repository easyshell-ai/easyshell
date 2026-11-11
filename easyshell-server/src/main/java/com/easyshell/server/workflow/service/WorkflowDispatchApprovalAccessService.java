package com.easyshell.server.workflow.service;

import com.easyshell.server.model.entity.WorkflowApproval;
import com.easyshell.server.model.entity.WorkflowDispatchApprovalBinding;
import com.easyshell.server.model.entity.WorkflowNodeRun;
import com.easyshell.server.repository.WorkflowNodeRunRepository;
import com.easyshell.server.repository.WorkflowRunRepository;
import com.easyshell.server.repository.WorkflowVersionRepository;
import com.easyshell.server.service.HostAclService;
import com.easyshell.server.workflow.gate.DispatchIntentHmac;
import com.easyshell.server.workflow.properties.WorkflowProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Access to a pending dispatch approval only; never grants access to the enclosing run.
 * The intent signature authenticates the stored script digest, not the approval preview.
 * Only dispatch consumption can compare that digest with the actual rendered script.
 * A separate detail HMAC authenticates the displayed, redacted snapshot only.
 */
@Service
public class WorkflowDispatchApprovalAccessService {
    private final WorkflowProperties properties;
    private final WorkflowRunProvenanceService provenance;
    private final WorkflowRunRepository runs;
    private final WorkflowVersionRepository versions;
    private final WorkflowNodeRunRepository nodes;
    private final JdbcTemplate jdbc;
    private final HostAclService acl;
    private final ObjectMapper mapper;
    private final EntityManager entityManager;

    public WorkflowDispatchApprovalAccessService(WorkflowProperties properties,
            WorkflowRunProvenanceService provenance, WorkflowRunRepository runs,
            WorkflowVersionRepository versions, WorkflowNodeRunRepository nodes,
            JdbcTemplate jdbc, HostAclService acl, ObjectMapper mapper, EntityManager entityManager) {
        this.properties = properties;
        this.provenance = provenance;
        this.runs = runs;
        this.versions = versions;
        this.nodes = nodes;
        this.jdbc = jdbc;
        this.acl = acl;
        this.mapper = mapper;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public boolean canView(Long actor, WorkflowApproval approval) {
        if (actor == null || actor <= 0 || approval == null || approval.getId() == null
                || approval.getId() <= 0 || !properties.getApproval().isDispatchEnabled()
                || !"script-exec".equals(approval.getNodeType())) return false;
        try {
            WorkflowApproval stored = entityManager.find(WorkflowApproval.class, approval.getId());
            if (stored == null || !Objects.equals(stored.getRunId(), approval.getRunId())
                    || !Objects.equals(stored.getNodeKey(), approval.getNodeKey())
                    || !Objects.equals(stored.getNodeType(), approval.getNodeType())
                    || !"pending".equals(stored.getStatus()) || !"pending".equals(approval.getStatus())
                    || stored.getDecidedBy() != null || stored.getDecidedAt() != null
                    || stored.getExpiresAt() == null || !stored.getExpiresAt().isAfter(LocalDateTime.now())) return false;
            String runId = stored.getRunId();
            if (provenance.verifiedIdentity(runId) != WorkflowRunProvenanceService.VerifiedIdentity.LEGACY)
                return false;
            var run = runs.findById(runId).orElse(null);
            if (run == null || !runId.equals(run.getId()) || !Set.of("running", "waiting").contains(run.getStatus())
                    || !Boolean.FALSE.equals(run.getCancelRequested()) || run.getStartedBy() == null
                    || run.getStartedBy() <= 0) return false;
            JsonNode input = tree(run.getInputJson());
            if (!input.isObject() || !input.isEmpty()) return false;
            var version = versions.findById(run.getVersionId()).orElse(null);
            if (version == null) return false; // verifiedIdentity checks the immutable version and SHA.
            List<Boolean> hostRecords = jdbc.query("SELECT source_kind, completed, task_count FROM "
                            + "workflow_run_provenance WHERE run_id = ?",
                    (rs, n) -> "HOST".equals(rs.getString(1)) && rs.getInt(2) == 1 && rs.getInt(3) >= 0, runId);
            if (hostRecords.size() != 1 || !hostRecords.get(0)) return false;

            WorkflowDispatchApprovalBinding binding = entityManager.find(WorkflowDispatchApprovalBinding.class, stored.getId());
            if (binding == null || !stored.getId().equals(binding.getApprovalId())
                    || binding.getDispatchNodeRunId() == null || binding.getDispatchNodeRunId().isBlank()
                    || binding.getDispatchAttempt() == null || binding.getDispatchAttempt() <= 0
                    || !Objects.equals(run.getStartedBy(), binding.getDispatchUserId())
                    || binding.getDispatchConsumedAt() != null || binding.getDispatchTaskId() != null
                    || binding.getDispatchTimeoutSec() == null || binding.getDispatchTimeoutSec() <= 0
                    || !digest(binding.getDispatchScriptHmac()) || !digest(binding.getDispatchIntentHmac())) return false;

            List<WorkflowNodeRun> allNodes = nodes.findByRunId(runId);
            List<WorkflowNodeRun> associated = allNodes.stream()
                    .filter(n -> stored.getId().equals(n.getApprovalId())).toList();
            if (associated.size() != 1) return false;
            WorkflowNodeRun current = associated.get(0);
            if (!binding.getDispatchNodeRunId().equals(current.getId()) || !runId.equals(current.getRunId())
                    || !Objects.equals(stored.getNodeKey(), current.getNodeKey())
                    || !"script-exec".equals(current.getNodeType())
                    || !Set.of("waiting", "running").contains(current.getStatus()) || current.getTaskId() != null
                    || !Objects.equals(binding.getDispatchAttempt(), current.getAttempt())) return false;
            for (WorkflowNodeRun other : allNodes) {
                if (!runId.equals(other.getRunId()) || other.getAttempt() == null || other.getAttempt() <= 0) return false;
                if (Objects.equals(current.getNodeKey(), other.getNodeKey()) && !Objects.equals(current.getId(), other.getId())
                        && (other.getAttempt() >= current.getAttempt() || "waiting".equals(other.getStatus()))) return false;
            }
            Long bindingCount = jdbc.queryForObject("SELECT COUNT(*) FROM workflow_dispatch_approval_binding "
                    + "WHERE dispatch_node_run_id = ? AND dispatch_attempt = ?", Long.class,
                    current.getId(), current.getAttempt());
            if (bindingCount == null || bindingCount != 1L) return false;
            Long nodeCount = jdbc.queryForObject("SELECT COUNT(*) FROM workflow_node_run WHERE approval_id = ?",
                    Long.class, stored.getId());
            if (nodeCount == null || nodeCount != 1L) return false;
            JsonNode definitionNodes = tree(version.getDefinitionJson()).path("nodes");
            if (!definitionNodes.isArray()) return false;
            int matches = 0;
            for (JsonNode node : definitionNodes) {
                if (Objects.equals(current.getNodeKey(), node.path("key").textValue())) {
                    if (!"script-exec".equals(node.path("type").textValue())) return false;
                    matches++;
                }
            }
            if (matches != 1) return false;

            List<DispatchIntentHmac.Target> targets = targets(binding.getDispatchTargets());
            if (!validSignature(runId, binding, targets)) return false;
            if (!validDetail(stored.getDetailJson(), runId, binding, targets)) return false;
            for (var target : targets) {
                if (!acl.allowedForGeneration(actor, target.agentId(), target.generation(), HostAclService.Permission.VIEW))
                    return false;
            }
            Long taskCount = jdbc.queryForObject("SELECT task_count FROM workflow_run_provenance WHERE run_id = ?",
                    Long.class, runId);
            Long associations = jdbc.queryForObject("SELECT COUNT(*) FROM workflow_run_provenance_task WHERE run_id = ?",
                    Long.class, runId);
            if (taskCount == null || associations == null || !taskCount.equals(associations)) return false;
            boolean hasTasks = taskCount > 0 || allNodes.stream().anyMatch(n -> n.getTaskId() != null);
            return !hasTasks || provenance.canView(actor, runId);
        } catch (Exception invalid) {
            return false;
        }
    }

    private JsonNode tree(String json) throws java.io.IOException {
        return mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(json);
    }

    private boolean validDetail(String json, String runId, WorkflowDispatchApprovalBinding binding,
                                List<DispatchIntentHmac.Target> bindingTargets) throws java.io.IOException {
        JsonNode value = tree(json);
        if (!(value instanceof ObjectNode detail)) return false;
        JsonNode signature = detail.remove("detailHmac");
        if (signature == null || !signature.isTextual() || !digest(signature.textValue())
                || detail.size() != 4 || !detail.path("scriptPreview").isTextual()
                || !detail.path("targets").isArray() || detail.path("targets").isEmpty()
                || !detail.path("timeoutSeconds").isIntegralNumber()
                || !detail.path("timeoutSeconds").canConvertToInt() || detail.path("timeoutSeconds").intValue() <= 0
                || !detail.path("riskLevel").isTextual()
                || !Set.of("MEDIUM", "HIGH").contains(detail.path("riskLevel").textValue())
                || detail.path("timeoutSeconds").intValue() != binding.getDispatchTimeoutSec()) return false;
        List<DispatchIntentHmac.Target> displayedTargets = targets(mapper.writeValueAsString(detail.path("targets")));
        if (!new HashSet<>(displayedTargets).equals(new HashSet<>(bindingTargets))) return false;
        // ObjectNode preserves the stored field order. Do not sort or rebuild the payload:
        // the producer signs LinkedHashMap JSON using the same default mapper settings.
        return matchesDetailSignature(signature.textValue(), mapper.writeValueAsString(detail), runId, binding, bindingTargets);
    }

    boolean matchesDetailSignature(String signature, String payloadJson, String runId,
                                   WorkflowDispatchApprovalBinding binding, List<DispatchIntentHmac.Target> targets) {
        DispatchIntentHmac hmac = DispatchIntentHmac.fromEnvironment();
        return DispatchIntentHmac.matches(signature, hmac.intentHmac(runId, binding.getDispatchNodeRunId(),
                binding.getDispatchAttempt(), binding.getDispatchUserId(), hmac.scriptHmac(payloadJson),
                targets, binding.getDispatchTimeoutSec()));
    }

    private List<DispatchIntentHmac.Target> targets(String json) throws java.io.IOException {
        JsonNode values = tree(json);
        if (values == null || !values.isArray() || values.isEmpty()) throw new IllegalArgumentException("Invalid targets");
        var result = new ArrayList<DispatchIntentHmac.Target>();
        var ids = new HashSet<String>();
        for (JsonNode value : values) {
            JsonNode id = value.path("agentId");
            JsonNode generation = value.path("generation");
            if (!value.isObject() || value.size() != 2 || !id.isTextual() || id.textValue().isBlank()
                    || id.textValue().length() > 64 || !ids.add(id.textValue())
                    || !generation.isIntegralNumber() || !generation.canConvertToLong() || generation.longValue() <= 0)
                throw new IllegalArgumentException("Invalid target identity");
            result.add(new DispatchIntentHmac.Target(id.textValue(), generation.longValue()));
        }
        return List.copyOf(result);
    }

    boolean validSignature(String runId, WorkflowDispatchApprovalBinding binding, List<DispatchIntentHmac.Target> targets) {
        return DispatchIntentHmac.matches(binding.getDispatchIntentHmac(), DispatchIntentHmac.fromEnvironment()
                .intentHmac(runId, binding.getDispatchNodeRunId(), binding.getDispatchAttempt(), binding.getDispatchUserId(),
                        binding.getDispatchScriptHmac(), targets, binding.getDispatchTimeoutSec()));
    }

    private boolean digest(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }
}
