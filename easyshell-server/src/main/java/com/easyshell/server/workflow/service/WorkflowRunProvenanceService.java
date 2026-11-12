package com.easyshell.server.workflow.service;

import com.easyshell.server.model.entity.WorkflowRun;
import com.easyshell.server.model.entity.WorkflowVersion;
import com.easyshell.server.repository.TaskTargetSnapshotRepository;
import com.easyshell.server.repository.WorkflowRunProvenanceRepository;
import com.easyshell.server.repository.WorkflowRunRepository;
import com.easyshell.server.repository.WorkflowRunV3BirthRepository;
import com.easyshell.server.repository.WorkflowVersionRepository;
import com.easyshell.server.service.HostAclService;
import com.easyshell.server.service.HostAclService.Permission;
import com.easyshell.server.workflow.expression.ExpressionEvaluator;
import com.easyshell.server.workflow.graph.DagValidator;
import com.easyshell.server.workflow.graph.ValidationIssue;
import com.easyshell.server.workflow.model.WorkflowDefinition;
import com.easyshell.server.workflow.model.NodeType;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

/** Host provenance is an additional authorization check, never a replacement for run ownership. */
@Service
public class WorkflowRunProvenanceService {
    private static final String NON_HOST_V1 = "NON_HOST_V1";
    private static final String NON_HOST_V2 = "NON_HOST_V2";
    private static final String NON_HOST_V3 = "NON_HOST_V3";
    private static final String NON_HOST_CODE = "NON_HOST_CODE";
    @Autowired
    private com.easyshell.server.workflow.code.WorkflowCodeWorkerClient codeWorker;
    @Autowired
    private WorkflowRunV3BirthRepository births;
    private final WorkflowRunProvenanceRepository provenance;
    private final WorkflowRunRepository runs;
    private final WorkflowVersionRepository versions;
    private final TaskTargetSnapshotRepository snapshots;
    private final HostAclService acl;
    private final ObjectMapper mapper;

    public WorkflowRunProvenanceService(WorkflowRunProvenanceRepository provenance, WorkflowRunRepository runs,
            WorkflowVersionRepository versions, TaskTargetSnapshotRepository snapshots, HostAclService acl,
            ObjectMapper mapper) {
        this.provenance = provenance; this.runs = runs; this.versions = versions;
        this.snapshots = snapshots; this.acl = acl; this.mapper = mapper;
    }

    /** Called only for a newly created run in the same transaction, before it can execute. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void initialize(WorkflowRun run, WorkflowVersion version) {
        if (!validVersion(run, version)) throw new IllegalArgumentException("Invalid immutable run definition");
        // V3 birth evidence is only meaningful once the execution boundary is active.
        // Never backfill runs created before that boundary is deployed.
        String kind = codeWorker != null && codeWorker.isAvailable() && nonHostCode(run, version)
                ? NON_HOST_CODE : nonHostV1(run, version) ? NON_HOST_V1 : "HOST";
        provenance.create(run.getId(), version.getId(), version.getDefinitionSha256(), kind);
    }

    public enum VerifiedIdentity { V3, LEGACY, UNKNOWN }

    /** Execution integrity only; this grants neither resource visibility nor dispatch authority. */
    public boolean matchesExecutionDefinition(String runId, WorkflowDefinition definition) {
        try {
            WorkflowRun run = runs.findById(runId).orElse(null);
            if (run == null || verifiedIdentity(runId) != VerifiedIdentity.LEGACY) return false;
            WorkflowVersion version = versions.findById(run.getVersionId()).orElse(null);
            return validVersion(run, version) && definition != null
                    && mapper.valueToTree(definition).equals(mapper.valueToTree(
                    mapper.readValue(version.getDefinitionJson(), WorkflowDefinition.class)));
        } catch (Exception failure) {
            return false;
        }
    }

    /** Identity evidence only; this does not grant content access or permit execution. */
    public VerifiedIdentity verifiedIdentity(String runId) {
        if (runId == null || runId.isBlank()) return VerifiedIdentity.UNKNOWN;
        try {
            WorkflowRun run = runs.findById(runId).orElse(null);
            var record = provenance.findComplete(runId).orElse(null);
            if (run == null || !runId.equals(run.getId()) || record == null || run.getVersionId() == null
                    || run.getVersionId() != record.versionId()) return VerifiedIdentity.UNKNOWN;
            WorkflowVersion version = versions.findById(run.getVersionId()).orElse(null);
            if (!validVersion(run, version) || !record.digest().equals(version.getDefinitionSha256()))
                return VerifiedIdentity.UNKNOWN;
            JsonNode input = mapper.readTree(run.getInputJson());
            if (input == null || !input.isObject()) return VerifiedIdentity.UNKNOWN;
            if (NON_HOST_V3.equals(record.kind())) {
                // A marker alone cannot prove the required execution semantics were active at birth.
                return VerifiedIdentity.UNKNOWN;
            }
            if (births.exists(runId)) return VerifiedIdentity.UNKNOWN;
            if (NON_HOST_CODE.equals(record.kind()) && record.taskIds().isEmpty() && nonHostCode(run, version))
                return VerifiedIdentity.LEGACY;
            if (NON_HOST_V1.equals(record.kind()) && record.taskIds().isEmpty() && nonHostV1(run, version))
                return VerifiedIdentity.LEGACY;
            if ("PURE".equals(record.kind()) && record.taskIds().isEmpty() && pureDefinition(run, version))
                return VerifiedIdentity.LEGACY;
            if ("HOST".equals(record.kind()) && knownNodeTypes(version)) return VerifiedIdentity.LEGACY;
            return VerifiedIdentity.UNKNOWN;
        } catch (Exception ex) { return VerifiedIdentity.UNKNOWN; }
    }

    /** Called in the task creation transaction, after its complete target snapshot is created. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void associateTask(String runId, String taskId) {
        if (snapshots.findComplete(taskId).isEmpty()) throw new IllegalStateException("Unknown task targets");
        provenance.associateTask(runId, taskId);
    }

    /** Fail closed on any missing, damaged or inaccessible source, including for administrators. */
    public boolean canView(Long userId, String runId) {
        if (userId == null || userId <= 0 || runId == null || runId.isBlank()) return false;
        try {
            WorkflowRun run = runs.findById(runId).orElse(null);
            var record = provenance.findComplete(runId).orElse(null);
            if (run == null || !runId.equals(run.getId()) || record == null || run.getVersionId() == null
                    || run.getVersionId() != record.versionId()) return false;
            WorkflowVersion version = versions.findById(run.getVersionId()).orElse(null);
            if (!validVersion(run, version) || !record.digest().equals(version.getDefinitionSha256())) return false;
            JsonNode input = mapper.readTree(run.getInputJson());
            if (input == null || !input.isObject()) return false;
            // A birth marker cannot authorize any kind until V3 execution is integrated.
            // Contradictory legacy markers and lookup errors must both fail closed.
            if (births.exists(runId)) return false;
            if (NON_HOST_CODE.equals(record.kind())) return record.taskIds().isEmpty() && nonHostCode(run, version);
            if (NON_HOST_V1.equals(record.kind()))
                return record.taskIds().isEmpty() && nonHostV1(run, version);
            if (NON_HOST_V2.equals(record.kind()))
                return false; // Approval comments have an independent, unproven source.
            if (NON_HOST_V3.equals(record.kind())) return false; // Execution boundary is not yet integrated.
            // Legacy PURE and HOST records keep their original, stricter input semantics.
            if (!input.isEmpty()) return false;
            if ("PURE".equals(record.kind())) return pureDefinition(run, version) && record.taskIds().isEmpty();
            if (!"HOST".equals(record.kind()) || !knownNodeTypes(version)) return false;
            if (record.taskIds().isEmpty()) return false;
            for (String taskId : record.taskIds()) {
                var snapshot = snapshots.findComplete(taskId).orElse(null);
                if (snapshot == null || !taskId.equals(snapshot.taskId()) || snapshot.targets().isEmpty()) return false;
                for (var target : snapshot.targets()) {
                    if (!acl.allowedForGeneration(userId, target.agentId(), target.generation(), Permission.VIEW)) return false;
                }
            }
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean knownNodeTypes(WorkflowVersion version) {
        try {
            JsonNode nodes = mapper.readTree(version.getDefinitionJson()).path("nodes");
            if (!nodes.isArray() || nodes.isEmpty()) return false;
            for (JsonNode node : nodes) {
                if (!node.isObject() || !node.path("type").isTextual()
                        || NodeType.fromCode(node.path("type").textValue()) == null) return false;
            }
            return true;
        } catch (Exception ex) { return false; }
    }

    /**
     * V1 trusts the authenticated manual request and immutable definition defaults as user data;
     * it does not infer whether text was copied from another run or host. The entire graph,
     * including disconnected nodes, must be validated and limited to data-only executors.
     * LLM uses only system/user messages, without host or tool injection. Changes to those
     * executors or to these trust assumptions require a new provenance semantics version.
     */
    private boolean nonHostV1(WorkflowRun run, WorkflowVersion version) {
        return nonHost(run, version, Set.of("start", "wait", "end", "condition", "transform", "llm"));
    }

    private boolean nonHostCode(WorkflowRun run, WorkflowVersion version) {
        try {
            boolean hasCode = false;
            for (JsonNode node : mapper.readTree(version.getDefinitionJson()).path("nodes")) {
                hasCode |= "code".equals(node.path("type").asText());
            }
            return hasCode && nonHost(run, version, Set.of("start", "wait", "end", "condition", "transform", "llm", "code"));
        } catch (Exception invalid) { return false; }
    }

    private boolean nonHost(WorkflowRun run, WorkflowVersion version, Set<String> safe) {
        try {
            if (!"manual".equals(run.getTriggerType()) || run.getStartedBy() == null
                    || run.getStartedBy() <= 0) return false;
            JsonNode input = mapper.readTree(run.getInputJson());
            if (input == null || !input.isObject()) return false;
            JsonNode tree = mapper.readTree(version.getDefinitionJson());
            if (tree == null || !tree.isObject() || !tree.path("nodes").isArray()
                    || tree.path("nodes").isEmpty()) return false;
            for (JsonNode node : tree.path("nodes")) {
                if (!node.isObject() || !node.path("type").isTextual()
                        || !safe.contains(node.path("type").textValue())) return false;
            }
            WorkflowDefinition definition = mapper.copy()
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .treeToValue(tree, WorkflowDefinition.class);
            return new DagValidator(new ExpressionEvaluator(mapper)).validate(definition).stream()
                    .noneMatch(issue -> issue.severity() == ValidationIssue.Severity.ERROR);
        } catch (Exception ex) { return false; }
    }

    private boolean validVersion(WorkflowRun run, WorkflowVersion version) {
        try {
            return run != null && run.getId() != null && !run.getId().isBlank() && version != null
                    && Boolean.TRUE.equals(version.getIsValid()) && version.getId() != null
                    && version.getId().equals(run.getVersionId()) && version.getWorkflowId() != null
                    && version.getWorkflowId().equals(run.getWorkflowId()) && version.getVersion() != null
                    && version.getVersion().equals(run.getVersionNo()) && version.getDefinitionJson() != null
                    && HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                            version.getDefinitionJson().getBytes(StandardCharsets.UTF_8))).equals(version.getDefinitionSha256());
        } catch (Exception ex) { return false; }
    }

    /** Deliberately narrow proof: no external sources, caller input, or unrecognized node types. */
    private boolean pureDefinition(WorkflowRun run, WorkflowVersion version) {
        try {
            JsonNode definition = mapper.readTree(version.getDefinitionJson());
            JsonNode nodes = definition.path("nodes");
            JsonNode variables = definition.path("variables");
            JsonNode input = mapper.readTree(run.getInputJson());
            if (!definition.isObject() || !nodes.isArray() || nodes.isEmpty()
                    || !(variables.isMissingNode() || (variables.isObject() && variables.isEmpty()))
                    || input == null || !input.isObject() || !input.isEmpty()) return false;
            Set<String> safe = Set.of("start", "wait", "end");
            for (JsonNode node : nodes) {
                if (!node.isObject() || !node.path("type").isTextual() || !safe.contains(node.path("type").asText()))
                    return false;
            }
            return true;
        } catch (Exception ex) { return false; }
    }
}
