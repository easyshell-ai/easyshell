package com.easyshell.server.workflow.gate;

import com.easyshell.server.repository.WorkflowApprovalRepository;
import com.easyshell.server.repository.WorkflowDispatchApprovalBindingRepository;
import com.easyshell.server.repository.WorkflowDispatchApprovalBindingRepository.Evidence;
import com.easyshell.server.repository.WorkflowRunRepository;
import com.easyshell.server.repository.WorkflowNodeRunRepository;
import com.easyshell.server.model.entity.WorkflowApproval;
import com.easyshell.server.model.entity.WorkflowRun;
import com.easyshell.server.model.entity.WorkflowNodeRun;
import com.easyshell.server.service.DispatchAuthority;
import com.easyshell.server.service.DispatchEvidenceVerifier;
import com.easyshell.server.service.HostAclService;
import com.easyshell.server.workflow.service.WorkflowRunProvenanceService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 工作流侧 {@link DispatchAuthority#HUMAN_APPROVED} 的证据校验器。
 *
 * <p>仅同一事务内预先锁定的 attempt 及完整实际派发上下文可消费证据。
 * 旧的无上下文校验入口始终拒绝。</p>
 */
@Service
public class WorkflowApprovalEvidenceVerifier implements DispatchEvidenceVerifier {

    private static final Pattern REFERENCE = Pattern.compile("workflow_approval:([A-Za-z0-9_-]{1,64}):([1-9][0-9]*)");
    private final WorkflowApprovalRepository approvals;
    private WorkflowRunRepository runs;
    private WorkflowNodeRunRepository nodes;
    private WorkflowDispatchApprovalBindingRepository repository;
    private WorkflowDispatchApprovalBindingService bindings;
    private WorkflowRunProvenanceService provenance;
    private HostAclService acl;
    private EntityManager entityManager;
    private ObjectMapper mapper;
    @Autowired
    private com.easyshell.server.workflow.properties.WorkflowProperties properties;

    public WorkflowApprovalEvidenceVerifier(WorkflowApprovalRepository approvalRepository) {
        this.approvals = approvalRepository;
    }

    @Autowired
    void setDependencies(WorkflowRunRepository runs, WorkflowNodeRunRepository nodes,
                         WorkflowDispatchApprovalBindingRepository repository,
                         WorkflowDispatchApprovalBindingService bindings, WorkflowRunProvenanceService provenance,
                         HostAclService acl, EntityManager entityManager, ObjectMapper mapper) {
        this.runs = runs;
        this.nodes = nodes;
        this.repository = repository;
        this.bindings = bindings;
        this.provenance = provenance;
        this.acl = acl;
        this.entityManager = entityManager;
        this.mapper = mapper;
    }

    private record Reference(String runId, long approvalId) { }
    private record Prepared(Reference reference, String nodeRunId, String nodeKey, int attempt, long userId,
                            Object transactionResource, TransactionSynchronization synchronization)
            implements PreparedEvidence { }

    private static Reference parse(String value) {
        if (value == null) return null;
        var match = REFERENCE.matcher(value);
        if (!match.matches()) return null;
        try {
            return new Reference(match.group(1), Long.parseLong(match.group(2)));
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PreparedEvidence prepare(String evidenceRef, Long userId) {
        Reference ref = parse(evidenceRef);
        if (properties == null || !properties.getApproval().isDispatchEnabled()
                || ref == null || userId == null || userId <= 0 || !transactionActive()) return null;
        Object resource = TransactionSynchronizationManager.getResource(entityManager.getEntityManagerFactory());
        if (resource == null) return null;
        WorkflowRun run = runs.lockById(ref.runId()).orElse(null);
        if (run == null) return null;
        entityManager.refresh(run, LockModeType.PESSIMISTIC_WRITE);
        WorkflowApproval approval = approvals.findById(ref.approvalId()).orElse(null);
        Evidence evidence = repository.find(ref.approvalId()).orElse(null);
        if (approval == null || evidence == null || evidence.attempt() == null || evidence.attempt() <= 0
                || evidence.nodeRunId() == null || evidence.nodeRunId().isBlank()) return null;
        entityManager.refresh(approval);
        if (!validApproval(ref, approval) || !Objects.equals(evidence.userId(), userId)) return null;
        WorkflowNodeRun node = nodes.lockRunningAttempt(evidence.nodeRunId(), ref.runId(),
                approval.getNodeKey(), evidence.attempt()).orElse(null);
        if (node == null) return null;
        entityManager.refresh(node, LockModeType.PESSIMISTIC_WRITE);
        if (!validState(ref, userId, run, node, approval, evidence) || !latestAttempt(node)
                || !validSignature(ref, evidence)) return null;
        TransactionSynchronization marker = new TransactionSynchronization() { };
        TransactionSynchronizationManager.registerSynchronization(marker);
        return new Prepared(ref, node.getId(), node.getNodeKey(), node.getAttempt(), userId, resource, marker);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public String verifyAndConsume(PreparedEvidence prepared, String evidenceRef, DispatchContext context) {
        if (!(prepared instanceof Prepared p) || context == null || !transactionActive()
                || !Objects.equals(p.reference(), parse(evidenceRef))
                || !Objects.equals(context.userId(), p.userId())
                || context.scriptContent() == null || context.scriptContent().isBlank()
                || context.targets() == null || context.targets().isEmpty()
                || context.timeoutSeconds() <= 0
                || context.taskId() == null || context.taskId().isBlank() || context.taskId().length() > 64
                || TransactionSynchronizationManager.getResource(entityManager.getEntityManagerFactory()) != p.transactionResource()
                || !TransactionSynchronizationManager.getSynchronizations().contains(p.synchronization())) return null;
        // The same transaction still owns the locks taken by prepare; never acquire run/node after identity here.
        WorkflowRun run = entityManager.find(WorkflowRun.class, p.reference().runId());
        WorkflowNodeRun node = entityManager.find(WorkflowNodeRun.class, p.nodeRunId());
        WorkflowApproval approval = approvals.findById(p.reference().approvalId()).orElse(null);
        Evidence evidence = repository.find(p.reference().approvalId()).orElse(null);
        if (run == null || node == null || approval == null || evidence == null) return null;
        entityManager.refresh(run);
        entityManager.refresh(node);
        entityManager.refresh(approval);
        if (!Objects.equals(node.getNodeKey(), p.nodeKey()) || !Objects.equals(node.getAttempt(), p.attempt())
                || !validState(p.reference(), p.userId(), run, node, approval, evidence) || !latestAttempt(node)) return null;
        List<DispatchIntentHmac.Target> targets;
        try {
            targets = context.targets().stream().map(t -> new DispatchIntentHmac.Target(t.agentId(), t.generation())).toList();
            DispatchIntentHmac hmac = DispatchIntentHmac.fromEnvironment();
            String scriptHmac = hmac.scriptHmac(context.scriptContent());
            if (!DispatchIntentHmac.matches(evidence.scriptHmac(), scriptHmac)
                    || !DispatchIntentHmac.matches(evidence.intentHmac(), hmac.intentHmac(p.reference().runId(),
                    p.nodeRunId(), p.attempt(), p.userId(), scriptHmac, targets, context.timeoutSeconds()))) return null;
            for (var target : targets) {
                if (!acl.allowedForGeneration(p.userId(), target.agentId(), target.generation(), HostAclService.Permission.EXECUTE))
                    return null;
            }
        } catch (RuntimeException invalid) {
            return null;
        }
        // Do not swallow a failed conditional UPDATE: its exception must roll back the caller's transaction.
        bindings.consume(p.reference().approvalId(), new WorkflowDispatchApprovalBindingService.Intent(
                p.reference().runId(), p.nodeRunId(), p.attempt(), p.userId(), context.scriptContent(),
                targets, context.timeoutSeconds()), context.taskId());
        return evidenceRef;
    }

    private boolean transactionActive() {
        return entityManager != null && TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive();
    }

    private boolean validApproval(Reference ref, WorkflowApproval approval) {
        return Objects.equals(approval.getId(), ref.approvalId()) && ref.runId().equals(approval.getRunId())
                && "script-exec".equals(approval.getNodeType()) && "approved".equals(approval.getStatus())
                && approval.getNodeKey() != null && !approval.getNodeKey().isBlank()
                && approval.getDecidedBy() != null && approval.getDecidedBy() > 0 && approval.getDecidedAt() != null
                && approval.getExpiresAt() != null && approval.getExpiresAt().isAfter(LocalDateTime.now());
    }

    private boolean validState(Reference ref, Long userId, WorkflowRun run, WorkflowNodeRun node,
                               WorkflowApproval approval, Evidence evidence) {
        return validApproval(ref, approval) && ref.runId().equals(run.getId()) && "running".equals(run.getStatus())
                && Boolean.FALSE.equals(run.getCancelRequested()) && Objects.equals(run.getStartedBy(), userId)
                && ref.runId().equals(node.getRunId()) && "running".equals(node.getStatus())
                && "script-exec".equals(node.getNodeType()) && node.getTaskId() == null
                && Objects.equals(node.getApprovalId(), ref.approvalId())
                && Objects.equals(node.getNodeKey(), approval.getNodeKey())
                && evidence.approvalId() == ref.approvalId() && Objects.equals(evidence.nodeRunId(), node.getId())
                && Objects.equals(evidence.attempt(), node.getAttempt()) && Objects.equals(evidence.userId(), userId)
                && evidence.consumedAt() == null && evidence.taskId() == null
                && provenance.verifiedIdentity(ref.runId()) == WorkflowRunProvenanceService.VerifiedIdentity.LEGACY;
    }

    private boolean latestAttempt(WorkflowNodeRun node) {
        return nodes.findFirstByRunIdAndNodeKeyOrderByAttemptDesc(node.getRunId(), node.getNodeKey())
                .filter(latest -> Objects.equals(latest.getId(), node.getId())
                        && Objects.equals(latest.getAttempt(), node.getAttempt())).isPresent();
    }

    private boolean validSignature(Reference ref, Evidence evidence) {
        try {
            List<DispatchIntentHmac.Target> targets = mapper.readerFor(new TypeReference<List<DispatchIntentHmac.Target>>() { })
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(evidence.targets());
            return evidence.timeoutSec() != null && DispatchIntentHmac.matches(evidence.intentHmac(),
                    DispatchIntentHmac.fromEnvironment().intentHmac(ref.runId(), evidence.nodeRunId(), evidence.attempt(),
                            evidence.userId(), evidence.scriptHmac(), targets, evidence.timeoutSec()));
        } catch (Exception invalid) {
            return false;
        }
    }

    @Override
    public DispatchAuthority supports() {
        return DispatchAuthority.HUMAN_APPROVED;
    }

    @Override
    public String verify(String evidenceRef, String scriptContent, Long userId) {
        return null; // 门禁返回 449：派发授权证据不成立，已拒绝派发。
    }
}
