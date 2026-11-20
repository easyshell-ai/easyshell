package com.easyshell.server.workflow.gate;

import com.easyshell.server.repository.WorkflowApprovalRepository;
import com.easyshell.server.repository.WorkflowRunRepository;
import com.easyshell.server.repository.WorkflowNodeRunRepository;
import com.easyshell.server.repository.WorkflowDispatchApprovalBindingRepository;
import com.easyshell.server.model.entity.WorkflowRun;
import com.easyshell.server.model.entity.WorkflowNodeRun;
import com.easyshell.server.model.entity.WorkflowApproval;
import com.easyshell.server.model.dto.TaskTargetSnapshot;
import com.easyshell.server.service.HostAclService;
import com.easyshell.server.workflow.service.WorkflowRunProvenanceService;
import com.easyshell.server.service.DispatchEvidenceVerifier.DispatchContext;
import com.easyshell.server.service.DispatchEvidenceVerifier.PreparedEvidence;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;
import java.util.Base64;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** No Spring context or environment key is needed to prove rejection before preparation. */
class WorkflowApprovalContextEvidenceVerifierTest {
    private final WorkflowApprovalRepository approvals = mock(WorkflowApprovalRepository.class);
    private final WorkflowApprovalEvidenceVerifier verifier = new WorkflowApprovalEvidenceVerifier(approvals);

    @Test
    void contextCannotAuthorizeWithoutPreparation() {
        assertThat(verifier.verifyAndConsume(null, "workflow_approval:run-1:7", context())).isNull();
        assertThat(verifier.verifyAndConsume(new PreparedEvidence() { }, "workflow_approval:run-1:7", context())).isNull();
        assertThat(verifier.verifyAndConsume(new PreparedEvidence() { }, "workflow_approval:run-1:7", null)).isNull();
        verifyNoInteractions(approvals);
    }

    @Test
    void validReferenceCannotPrepareWithoutBoundTransaction() {
        assertThat(verifier.prepare("workflow_approval:run-1:7", 42L)).isNull();
        verifyNoInteractions(approvals);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"task-7", "workflow_approval:run-1:0", "workflow_approval:run-1:-1",
            "workflow_approval:run-1:01", "workflow_approval:run-1:7:extra", "workflow_approval::7",
            "workflow_approval:run-1:9223372036854775808", " workflow_approval:run-1:7"})
    void malformedReferenceCannotPrepare(String reference) {
        assertThat(verifier.prepare(reference, 42L)).isNull();
        verifyNoInteractions(approvals);
    }

    @Test
    void legacyVerificationStillRejectsEveryContext() {
        assertThat(verifier.verify("workflow_approval:run-1:7", "script", 42L)).isNull();
        verifyNoInteractions(approvals);
    }

    private DispatchContext context() {
        return new DispatchContext("script", 42L, List.of(), 30, "task-context");
    }

    @Test
    void preparedEvidenceRejectsChangedContextAndCannotCrossTransactions() {
        Fixture f = new Fixture();
        f.begin();
        try (var signing = mockStatic(DispatchIntentHmac.class, CALLS_REAL_METHODS)) {
            signing.when(DispatchIntentHmac::fromEnvironment).thenReturn(f.hmac);
            PreparedEvidence prepared = verifier.prepare(f.reference, 42L);
            assertThat(prepared).isNotNull();
            verifyNoInteractions(f.bindings);
            assertThat(verifier.verifyAndConsume(prepared, f.reference, f.context("changed", 42L, f.targets, 30))).isNull();
            assertThat(verifier.verifyAndConsume(prepared, f.reference, f.context("script", 43L, f.targets, 30))).isNull();
            assertThat(verifier.verifyAndConsume(prepared, f.reference, f.context("script", 42L, f.targets, 31))).isNull();
            assertThat(verifier.verifyAndConsume(prepared, f.reference, f.context("script", 42L,
                    List.of(new TaskTargetSnapshot.Target("agent-1", 2)), 30))).isNull();
            assertThat(verifier.verifyAndConsume(prepared, "workflow_approval:run-1:8",
                    f.context("script", 42L, f.targets, 30))).isNull();
            verifyNoInteractions(f.bindings);

            // Even reusing the same resource holder cannot reuse a synchronization from a completed transaction.
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.initSynchronization();
            assertThat(verifier.verifyAndConsume(prepared, f.reference,
                    f.context("script", 42L, f.targets, 30))).isNull();
            verifyNoInteractions(f.bindings);
        } finally {
            f.end();
        }
    }

    @Test
    void validContextConsumesOnlyAfterPreparationAndPropagatesConsumeFailure() {
        Fixture f = new Fixture();
        f.begin();
        try (var signing = mockStatic(DispatchIntentHmac.class, CALLS_REAL_METHODS)) {
            signing.when(DispatchIntentHmac::fromEnvironment).thenReturn(f.hmac);
            PreparedEvidence prepared = verifier.prepare(f.reference, 42L);
            assertThat(prepared).isNotNull();
            when(f.acl.allowedForGeneration(42L, "agent-1", 1, HostAclService.Permission.EXECUTE)).thenReturn(true);
            assertThat(verifier.verifyAndConsume(prepared, f.reference,
                    f.context("script", 42L, f.targets, 30))).isEqualTo(f.reference);
            verify(f.bindings).consume(eq(7L), argThat(intent -> intent.runId().equals("run-1")
                    && intent.nodeRunId().equals("node-1") && intent.attempt() == 1 && intent.userId() == 42L
                    && intent.script().equals("script") && intent.timeoutSec() == 30), eq("task-context"));
            doThrow(new IllegalStateException("conditional consume failed")).when(f.bindings)
                    .consume(eq(7L), any(), eq("task-context"));
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> verifier.verifyAndConsume(prepared, f.reference,
                    f.context("script", 42L, f.targets, 30))).isInstanceOf(IllegalStateException.class);
        } finally {
            f.end();
        }
    }

    @Test
    void revokedExecutePermissionDoesNotConsume() {
        Fixture f = new Fixture();
        f.begin();
        try (var signing = mockStatic(DispatchIntentHmac.class, CALLS_REAL_METHODS)) {
            signing.when(DispatchIntentHmac::fromEnvironment).thenReturn(f.hmac);
            PreparedEvidence prepared = verifier.prepare(f.reference, 42L);
            assertThat(prepared).isNotNull();
            assertThat(verifier.verifyAndConsume(prepared, f.reference,
                    f.context("script", 42L, f.targets, 30))).isNull();
            verifyNoInteractions(f.bindings);
        } finally {
            f.end();
        }
    }

    /** Unit fixture models transaction identity only; it does not claim database rollback or locking evidence. */
    private class Fixture {
        final String reference = "workflow_approval:run-1:7";
        final WorkflowRunRepository runs = mock(WorkflowRunRepository.class);
        final WorkflowNodeRunRepository nodes = mock(WorkflowNodeRunRepository.class);
        final WorkflowDispatchApprovalBindingRepository repository = mock(WorkflowDispatchApprovalBindingRepository.class);
        final WorkflowDispatchApprovalBindingService bindings = mock(WorkflowDispatchApprovalBindingService.class);
        final WorkflowRunProvenanceService provenance = mock(WorkflowRunProvenanceService.class);
        final HostAclService acl = mock(HostAclService.class);
        final EntityManager em = mock(EntityManager.class);
        final EntityManagerFactory emf = mock(EntityManagerFactory.class);
        final List<TaskTargetSnapshot.Target> targets = List.of(new TaskTargetSnapshot.Target("agent-1", 1));
        final DispatchIntentHmac hmac = new DispatchIntentHmac(Base64.getEncoder().encodeToString(new byte[32]));

        void begin() {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.bindResource(emf, new Object());
            verifier.setDependencies(runs, nodes, repository, bindings, provenance, acl, em, new ObjectMapper());
            var properties = new com.easyshell.server.workflow.properties.WorkflowProperties();
            properties.getApproval().setDispatchEnabled(true);
            org.springframework.test.util.ReflectionTestUtils.setField(verifier, "properties", properties);
            when(em.getEntityManagerFactory()).thenReturn(emf);
            WorkflowRun run = new WorkflowRun();
            run.setId("run-1");
            run.setStatus("running");
            run.setCancelRequested(false);
            run.setStartedBy(42L);
            WorkflowNodeRun node = new WorkflowNodeRun();
            node.setId("node-1");
            node.setRunId("run-1");
            node.setNodeKey("exec");
            node.setNodeType("script-exec");
            node.setStatus("running");
            node.setAttempt(1);
            node.setApprovalId(7L);
            WorkflowApproval approval = new WorkflowApproval();
            approval.setId(7L);
            approval.setRunId("run-1");
            approval.setNodeKey("exec");
            approval.setNodeType("script-exec");
            approval.setStatus("approved");
            approval.setDecidedBy(99L);
            approval.setDecidedAt(LocalDateTime.now());
            approval.setExpiresAt(LocalDateTime.now().plusHours(1));
            String scriptHmac = hmac.scriptHmac("script");
            String intentHmac = hmac.intentHmac("run-1", "node-1", 1, 42L, scriptHmac,
                    List.of(new DispatchIntentHmac.Target("agent-1", 1)), 30);
            when(runs.lockById("run-1")).thenReturn(Optional.of(run));
            when(nodes.lockRunningAttempt("node-1", "run-1", "exec", 1)).thenReturn(Optional.of(node));
            when(nodes.findFirstByRunIdAndNodeKeyOrderByAttemptDesc("run-1", "exec")).thenReturn(Optional.of(node));
            when(approvals.findById(7L)).thenReturn(Optional.of(approval));
            when(repository.find(7L)).thenReturn(Optional.of(new WorkflowDispatchApprovalBindingRepository.Evidence(
                    7L, "node-1", 1, 42L, scriptHmac, intentHmac,
                    "[{\"agentId\":\"agent-1\",\"generation\":1}]", 30, null, null)));
            when(provenance.verifiedIdentity("run-1")).thenReturn(WorkflowRunProvenanceService.VerifiedIdentity.LEGACY);
            when(em.find(WorkflowRun.class, "run-1")).thenReturn(run);
            when(em.find(WorkflowNodeRun.class, "node-1")).thenReturn(node);
        }

        DispatchContext context(String script, Long user, List<TaskTargetSnapshot.Target> actual, int timeout) {
            return new DispatchContext(script, user, actual, timeout, "task-context");
        }

        void end() {
            TransactionSynchronizationManager.unbindResource(emf);
            TransactionSynchronizationManager.clear();
        }
    }
}
