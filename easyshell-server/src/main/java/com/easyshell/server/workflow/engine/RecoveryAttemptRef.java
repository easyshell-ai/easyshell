package com.easyshell.server.workflow.engine;

import com.easyshell.server.repository.WorkflowRecoveryClaimRepository.Claim;
import com.easyshell.server.repository.WorkflowRecoveryClaimRepository.Member;

import java.util.Objects;

/** Explicit immutable handoff; usable for delivery only after the claim transaction commits successfully. */
public record RecoveryAttemptRef(Claim claim, Member member) {
    public RecoveryAttemptRef {
        Objects.requireNonNull(claim);
        Objects.requireNonNull(member);
    }

    @Override
    public String toString() {
        return "RecoveryAttemptRef[runId=" + claim.runId() + ", round=" + claim.round()
                + ", owner=" + claim.owner() + ", member=" + member + ", token=<redacted>]";
    }
}
