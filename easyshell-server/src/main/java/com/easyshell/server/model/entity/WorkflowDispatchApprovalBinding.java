package com.easyshell.server.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Dispatch evidence associated with an approval; it is not an approval itself. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "workflow_dispatch_approval_binding")
public class WorkflowDispatchApprovalBinding {
    /** Soft reference and unique key: at most one binding per approval. */
    @Id
    @Column(name = "approval_id", nullable = false)
    private Long approvalId;

    @Column(name = "dispatch_node_run_id", nullable = false, length = 64)
    private String dispatchNodeRunId;

    @Column(name = "dispatch_attempt", nullable = false)
    private Integer dispatchAttempt;

    @Column(name = "dispatch_user_id", nullable = false)
    private Long dispatchUserId;

    @Column(name = "dispatch_script_hmac", nullable = false, length = 64)
    private String dispatchScriptHmac;

    @Column(name = "dispatch_intent_hmac", nullable = false, length = 64)
    private String dispatchIntentHmac;

    @Lob
    @Column(name = "dispatch_targets", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String dispatchTargets;

    @Column(name = "dispatch_timeout_sec", nullable = false)
    private Integer dispatchTimeoutSec;

    @Column(name = "dispatch_consumed_at")
    private LocalDateTime dispatchConsumedAt;

    @Column(name = "dispatch_task_id", length = 64)
    private String dispatchTaskId;
}
