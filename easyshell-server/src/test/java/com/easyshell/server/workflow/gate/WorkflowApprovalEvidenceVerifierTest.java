package com.easyshell.server.workflow.gate;

import com.easyshell.server.model.entity.WorkflowApproval;
import com.easyshell.server.repository.WorkflowApprovalRepository;
import com.easyshell.server.service.DispatchAuthority;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class WorkflowApprovalEvidenceVerifierTest {

    @Mock
    private WorkflowApprovalRepository repository;

    @Test
    void supportsOnlyHumanApproved() {
        assertThat(new WorkflowApprovalEvidenceVerifier(repository).supports())
                .isEqualTo(DispatchAuthority.HUMAN_APPROVED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"workflow_approval:run-1:7", "workflow_approval:other-run:7"})
    void approvedLegacyEvidenceCannotAuthorizeSameOrDifferentRun(String evidence) {
        WorkflowApproval approval = new WorkflowApproval();
        approval.setId(7L);
        approval.setRunId("run-1");
        approval.setStatus("approved");
        approval.setDecidedBy(99L);
        lenient().when(repository.findById(7L)).thenReturn(Optional.of(approval));

        WorkflowApprovalEvidenceVerifier verifier = new WorkflowApprovalEvidenceVerifier(repository);
        assertThat(verifier.verify(evidence, "high risk script", 42L)).isNull();
        assertThat(verifier.verify(evidence, "different script", 43L)).isNull();
        verifyNoInteractions(repository);
    }

    @Test
    void missingOrMalformedEvidenceIsRejected() {
        WorkflowApprovalEvidenceVerifier verifier = new WorkflowApprovalEvidenceVerifier(repository);
        assertThat(verifier.verify(null, "script", 42L)).isNull();
        assertThat(verifier.verify("workflow_approval:run-1:not-an-id", "script", 42L)).isNull();
        assertThat(verifier.verify("task-id", "script", 42L)).isNull();
        verifyNoInteractions(repository);
    }
}
