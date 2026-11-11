package com.easyshell.server.workflow.engine;

import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.workflow.properties.WorkflowProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashSet;
import java.util.List;

/** Bounded expiry bookkeeping, independent of automatic recovery. Never dispatches or adopts an owner. */
@Slf4j
@Component
public class WorkflowRecoveryClaimSweeper {
    private static final int LIMIT = 100;
    private final WorkflowRecoveryClaimService service;
    private final WorkflowProperties properties;
    private final AuditLogService audit;
    private final TransactionTemplate transactions;
    // Discovery cursor only; never authority. Advance even on conflicts so poison rows cannot starve later ids.
    private String afterRunId;

    public WorkflowRecoveryClaimSweeper(WorkflowRecoveryClaimService service, WorkflowProperties properties,
                                       AuditLogService audit, PlatformTransactionManager transactionManager) {
        this.service = service;
        this.properties = properties;
        this.audit = audit;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** At most 100 distinct run ids per pass; every run and its audit use one independent transaction. */
    @Scheduled(fixedDelayString = "${easyshell.workflow.engine.wakeup-interval-sec:10}000", initialDelay = 10000)
    public synchronized void sweep() {
        if (!properties.getEngine().isDurableResumeEnabled()) return;
        List<String> candidates;
        try {
            candidates = transactions.execute(status -> service.findExpiredRunIds(afterRunId, LIMIT));
            if (candidates == null) return;
            if (afterRunId != null && candidates.size() < LIMIT) {
                int remaining = LIMIT - candidates.size();
                List<String> wrapped = transactions.execute(status -> service.findExpiredRunIds(null, remaining));
                var unique = new LinkedHashSet<>(candidates);
                if (wrapped != null) unique.addAll(wrapped);
                candidates = List.copyOf(unique);
            }
        } catch (RuntimeException failure) {
            // No throwable/message: JDBC/transaction exceptions can contain SQL parameters and tokens.
            log.warn("恢复领取过期候选读取失败");
            return;
        }
        for (String runId : candidates) {
            afterRunId = runId;
            try {
                transactions.executeWithoutResult(status -> {
                    var result = service.sweepExpired(runId);
                    if (result.changed()) {
                        audit.logSync(null, "SYSTEM", "WORKFLOW_RECOVERY_CLAIM_EXPIRED", "workflow_run", runId,
                                "cancelled=" + result.cancelled() + ", uncertain=" + result.uncertain()
                                        + ", batchClosed=" + result.batchClosed(), null, "success");
                    }
                });
            } catch (RuntimeException failure) {
                String safeId = runId.matches("[A-Za-z0-9_-]{1,64}") ? runId : "<invalid>";
                log.warn("恢复领取过期收尾失败: run={}", safeId);
            }
        }
    }
}
