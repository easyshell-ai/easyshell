package com.easyshell.server.workflow.engine;

import com.easyshell.server.model.entity.WorkflowNodeRun;
import com.easyshell.server.repository.WorkflowNodeRunRepository;
import com.easyshell.server.repository.WorkflowRunRepository;
import com.easyshell.server.repository.WorkflowRecoveryClaimRepository.State;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * attempt 分配与终态写入（§3.6 / §3.2.2 第一、二类事务边界）。
 *
 * <p>{@code startAttempt} 与 {@code finishAttempt} 各是<b>独立短事务</b>（REQUIRES_NEW）：
 * 节点执行可能跑几分钟，绝不能让 attempt 行的事务跟着一起挂着。</p>
 *
 * <p>先锁 run，与取消串行；再核对调用方看到的最新 attempt，避免陈旧调度重复执行。
 * 唯一键仍作为不遵循此锁协议的写入者的最后防线，不改写已有 attempt。</p>
 */
@Slf4j
@Service
public class AttemptAllocator {

    private final WorkflowNodeRunRepository nodeRunRepository;
    private final WorkflowRunRepository runRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WorkflowRecoveryClaimService recoveryClaims;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    /**
     * 显式事务模板（PROPAGATION_REQUIRES_NEW）而不是 {@code @Transactional}：
     * 调用方是引擎线程池里手动装配的对象图，不保证一定经过 Spring 代理，
     * 用模板把"独立短事务"变成代码里可见的事实，避免注解被静默跳过。
     */
    private final TransactionTemplate transactionTemplate;

    public AttemptAllocator(WorkflowNodeRunRepository nodeRunRepository, WorkflowRunRepository runRepository,
                            PlatformTransactionManager transactionManager) {
        this.nodeRunRepository = nodeRunRepository;
        this.runRepository = runRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 抢占一个 attempt：INSERT 一行 running 的 node_run。
     *
     * @return 新插入的 attempt 行（status=running，startedAt=now）
     * @throws AttemptConflictException uk_node_run 冲突：另一执行者已接管
     */
    public WorkflowNodeRun startAttempt(String runId, String nodeKey, String nodeType) {
        return startAttempt(runId, nodeKey, nodeType, 0);
    }

    /** expectedPrevious 是调用方决定重试时看到的失败 attempt；首次启动传 0。 */
    public WorkflowNodeRun startAttempt(String runId, String nodeKey, String nodeType, int expectedPrevious) {
        try {
            return transactionTemplate.execute(status -> {
                var run = runRepository.lockById(runId).orElse(null);
                if (run == null || !"running".equals(run.getStatus()) || !Boolean.FALSE.equals(run.getCancelRequested())) {
                    return null;
                }
                var previous = nodeRunRepository.findFirstByRunIdAndNodeKeyOrderByAttemptDesc(runId, nodeKey);
                if (previous.isPresent() ? previous.get().getAttempt() != expectedPrevious
                        || !("failed".equals(previous.get().getStatus()) || "timeout".equals(previous.get().getStatus()))
                        : expectedPrevious != 0) {
                    return null;
                }
                int next = expectedPrevious + 1;
                WorkflowNodeRun row = new WorkflowNodeRun();
                row.setId(newNodeRunId());
                row.setRunId(runId);
                row.setNodeKey(nodeKey);
                row.setNodeType(nodeType);
                row.setAttempt(next);
                row.setStatus("running");
                row.setStartedAt(LocalDateTime.now());
                return nodeRunRepository.saveAndFlush(row);
            });
        } catch (DataIntegrityViolationException e) {
            // uk_node_run 兜底（MF-10）：冲突既可能在 flush 期暴露，也可能在 commit 期
            // （H2 行锁会让后到者先阻塞再失败），两种都按"另一个执行者已接管"处理。
            // 留痕放在新事务里：原事务已被标记回滚，不能在里面再写。
            int blocking = nodeRunRepository.findMaxAttempt(runId, nodeKey).orElse(1);
            log.warn("attempt 抢占失败: run={} node={} attempt={}", runId, nodeKey, blocking);
            throw new AttemptConflictException(runId, nodeKey, blocking);
        }
    }

    public int finishAttempt(WorkflowNodeRun row, AttemptResult result) {
        return finishAttempt(row, result, outcome -> {});
    }

    public int finishAttempt(WorkflowNodeRun row, AttemptResult result, Consumer<String> staleCommitted) {
        return finishAttempt(row, result, staleCommitted, false);
    }

    public int finishAttempt(WorkflowNodeRun row, AttemptResult result, Consumer<String> staleCommitted,
                             boolean preserveApprovalBinding) {
        return finishAttempt(row, result, staleCommitted, preserveApprovalBinding, null, null);
    }

    public int finishAttempt(WorkflowNodeRun row, AttemptResult result, Consumer<String> staleCommitted,
                             boolean preserveApprovalBinding, RecoveryAttemptRef ref, State recoveryState) {
        return finishAttempt(row, result, staleCommitted, preserveApprovalBinding, ref, recoveryState, null);
    }

    public int finishAttempt(WorkflowNodeRun row, AttemptResult result, Consumer<String> staleCommitted,
                             boolean preserveApprovalBinding, RecoveryAttemptRef ref, State recoveryState,
                             String errorCode) {
        return transactionTemplate.execute(status -> {
            var run = runRepository.lockById(row.getRunId()).orElse(null);
            if (run == null) return 0;
            if (ref != null) {
                requireMatchingRef(row, ref);
                State outcome = recoveryState == State.UNCERTAIN
                        ? ordinaryApprovalOutcome(row, ref, errorCode) : recoveryState;
                if (outcome == null || !recoveryClaims.finish(ref, outcome)) {
                    throw new IllegalStateException("recovery completion conflict: " + row.getId());
                }
                // Unknown execution keeps its node running and its durable slot occupied.
                if (outcome == State.UNCERTAIN) return 0;
            }
            if (!"running".equals(run.getStatus()) || !Boolean.FALSE.equals(run.getCancelRequested())) {
                // 仅收尾本地记录：已提交的外部副作用可能仍在运行，不能声称取消已送达。
                String outcome = "timeout".equals(run.getStatus()) ? "timeout" : "cancelled";
                int updated = nodeRunRepository.closeStaleAttempt(row.getId(), outcome,
                        result.inputJson(), result.taskId(),
                        "run 已结束或取消，迟到结果已丢弃；外部执行可能仍在运行",
                        LocalDateTime.now(), result.durationMs());
                if (ref != null && updated != 1) {
                    throw new IllegalStateException("recovery node completion conflict: " + row.getId());
                }
                if (updated == 1) {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            staleCommitted.accept(outcome);
                        }
                    });
                }
                // 0 告知调用者结果不可合并，即使本次已把 running 记录收尾。
                return 0;
            }
            int updated = nodeRunRepository.finishAttemptAtomically(
                row.getId(),
                result.status(),
                result.inputJson(),
                result.outputJson(),
                result.outputSha256(),
                result.previewSha256(),
                result.rawParams(),
                result.artifactIds(),
                result.errorMessage(),
                result.warningMessage(),
                result.agentVersions(),
                result.taskId(),
                result.approvalId(),
                preserveApprovalBinding,
                result.cancelDelivered(),
                LocalDateTime.now(),
                result.durationMs());
            if (ref != null && updated != 1) {
                throw new IllegalStateException("recovery node completion conflict: " + row.getId());
            }
            return updated;
        });
    }

    /** A code alone is not evidence: require the persisted ordinary approval and exact attempt binding. */
    private State ordinaryApprovalOutcome(WorkflowNodeRun expected, RecoveryAttemptRef ref, String errorCode) {
        if (!"approval".equals(expected.getNodeType()) || entityManager == null) return State.UNCERTAIN;
        String decision = switch (errorCode == null ? "" : errorCode) {
            case "approval_rejected" -> "rejected";
            case "approval_timeout" -> "timeout";
            case "approval_cancelled" -> "cancelled";
            default -> null;
        };
        if (decision == null) return State.UNCERTAIN;
        var lock = jakarta.persistence.LockModeType.PESSIMISTIC_WRITE;
        WorkflowNodeRun actual = entityManager.find(WorkflowNodeRun.class, ref.member().nodeRunId(), lock);
        if (actual == null) return State.UNCERTAIN;
        entityManager.refresh(actual, lock);
        requireMatchingRef(actual, ref);
        if (!"running".equals(actual.getStatus()) || !"approval".equals(actual.getNodeType())
                || actual.getTaskId() != null || expected.getTaskId() != null || actual.getApprovalId() == null
                || !java.util.Objects.equals(actual.getApprovalId(), expected.getApprovalId())) {
            return State.UNCERTAIN;
        }
        var approval = entityManager.find(com.easyshell.server.model.entity.WorkflowApproval.class,
                actual.getApprovalId(), lock);
        if (approval == null) return State.UNCERTAIN;
        entityManager.refresh(approval, lock);
        if (!java.util.Objects.equals(actual.getRunId(), approval.getRunId())
                || !java.util.Objects.equals(actual.getNodeKey(), approval.getNodeKey())
                || !"approval".equals(approval.getNodeType()) || !decision.equals(approval.getStatus())) {
            return State.UNCERTAIN;
        }
        return "cancelled".equals(decision) ? State.CANCELLED : State.FAILED;
    }

    /** Each barrier returns only after its independent transaction committed. */
    public boolean enterRecovery(RecoveryAttemptRef ref) {
        return Boolean.TRUE.equals(transactionTemplate.execute(tx -> {
            var run = runRepository.lockById(ref.claim().runId()).orElse(null);
            return run != null && "running".equals(run.getStatus())
                    && Boolean.FALSE.equals(run.getCancelRequested()) && recoveryClaims.enter(ref);
        }));
    }

    public boolean verifyRecoveryEntered(RecoveryAttemptRef ref) {
        return Boolean.TRUE.equals(transactionTemplate.execute(tx -> {
            var run = runRepository.lockById(ref.claim().runId()).orElse(null);
            return run != null && "running".equals(run.getStatus())
                    && Boolean.FALSE.equals(run.getCancelRequested()) && recoveryClaims.verifyEntered(ref);
        }));
    }

    /** Best-effort conservative closure of ENTERED work with no committed result. Never grants delivery. */
    public boolean markRecoveryUncertain(RecoveryAttemptRef ref) {
        return Boolean.TRUE.equals(transactionTemplate.execute(tx -> {
            if (runRepository.lockById(ref.claim().runId()).isEmpty()) return false;
            return recoveryClaims.finish(ref, State.UNCERTAIN);
        }));
    }

    /** Only a definite pool rejection permits ENTERED to become a cancellation tombstone. */
    public int closeRecoveryAborted(RecoveryAttemptRef ref, boolean rejected, Consumer<Void> committed) {
        return transactionTemplate.execute(tx -> {
            if (runRepository.lockById(ref.claim().runId()).isEmpty()) return 0;
            boolean changed = rejected ? recoveryClaims.rejectEntered(ref) : recoveryClaims.abortUnentered(ref);
            if (!changed) return 0;
            var member = ref.member();
            int updated = nodeRunRepository.closeResumeAbortedAttempt(member.nodeRunId(), ref.claim().runId(),
                    member.nodeKey(), member.attempt(), "resume_aborted: 恢复在执行此节点前中止",
                    LocalDateTime.now(), 0L);
            if (updated != 1) throw new IllegalStateException("recovery abort conflict: " + member.nodeRunId());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() { committed.accept(null); }
            });
            return updated;
        });
    }

    private static void requireMatchingRef(WorkflowNodeRun row, RecoveryAttemptRef ref) {
        var member = ref.member();
        if (!java.util.Objects.equals(row.getRunId(), ref.claim().runId())
                || !java.util.Objects.equals(row.getId(), member.nodeRunId())
                || !java.util.Objects.equals(row.getNodeKey(), member.nodeKey())
                || !java.util.Objects.equals(row.getAttempt(), member.attempt())) {
            throw new IllegalArgumentException("recovery attempt identity mismatch");
        }
    }

    /**
     * Close a resume claim that was never accepted by the execution pool.
     * This deliberately does not use finishAttempt: its stale branch represents
     * a late executor result and cannot retain the resume-specific reason.
     */
    public int closeResumeAborted(String runId, String nodeKey, String attemptId, int attempt,
                                  String errorMessage, Consumer<Void> committed) {
        return transactionTemplate.execute(status -> {
            var run = runRepository.lockById(runId).orElse(null);
            if (run == null || !("cancelled".equals(run.getStatus())
                    || "failed".equals(run.getStatus()) || "timeout".equals(run.getStatus()))) {
                return 0;
            }
            LocalDateTime now = LocalDateTime.now();
            int updated = nodeRunRepository.closeResumeAbortedAttempt(
                    attemptId, runId, nodeKey, attempt, errorMessage, now, 0L);
            if (updated == 1) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        committed.accept(null);
                    }
                });
            }
            return updated;
        });
    }

    /** attempt 终态字段集合（避免 15 参方法在调用处散落） */
    public record AttemptResult(String status, String inputJson, String outputJson, String outputSha256,
                                String previewSha256,
                                String rawParams, String artifactIds, String errorMessage, String warningMessage,
                                String agentVersions, String taskId, Long approvalId, boolean cancelDelivered,
                                Long durationMs) {

        public static AttemptResult.Builder builder() {
            return new AttemptResult.Builder();
        }

        public static final class Builder {
            private String status;
            private String inputJson;
            private String outputJson;
            private String outputSha256;
            private String previewSha256;
            private String rawParams;
            private String artifactIds;
            private String errorMessage;
            private String warningMessage;
            private String agentVersions;
            private String taskId;
            private Long approvalId;
            private boolean cancelDelivered;
            private Long durationMs;

            public Builder status(String status) {
                this.status = status;
                return this;
            }

            /** 渲染后的 parameters Map + rawParams 声明（MF-13；执行体回填在 nodeRun 行上） */
            public Builder inputJson(String inputJson) {
                this.inputJson = inputJson;
                return this;
            }

            public Builder outputJson(String outputJson) {
                this.outputJson = outputJson;
                return this;
            }

            public Builder outputSha256(String outputSha256) {
                this.outputSha256 = outputSha256;
                return this;
            }

            public Builder previewSha256(String previewSha256) {
                this.previewSha256 = previewSha256;
                return this;
            }

            public Builder rawParams(String rawParams) {
                this.rawParams = rawParams;
                return this;
            }

            public Builder artifactIds(String artifactIds) {
                this.artifactIds = artifactIds;
                return this;
            }

            public Builder errorMessage(String errorMessage) {
                this.errorMessage = errorMessage;
                return this;
            }

            public Builder warningMessage(String warningMessage) {
                this.warningMessage = warningMessage;
                return this;
            }

            public Builder agentVersions(String agentVersions) {
                this.agentVersions = agentVersions;
                return this;
            }

            public Builder taskId(String taskId) {
                this.taskId = taskId;
                return this;
            }

            public Builder approvalId(Long approvalId) {
                this.approvalId = approvalId;
                return this;
            }

            public Builder cancelDelivered(boolean cancelDelivered) {
                this.cancelDelivered = cancelDelivered;
                return this;
            }

            public Builder durationMs(Long durationMs) {
                this.durationMs = durationMs;
                return this;
            }

            public AttemptResult build() {
                return new AttemptResult(status, inputJson, outputJson, outputSha256, previewSha256,
                        rawParams, artifactIds, errorMessage, warningMessage, agentVersions, taskId,
                        approvalId, cancelDelivered, durationMs);
            }
        }
    }

    private static String newNodeRunId() {
        return "wnr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
