package com.easyshell.server.workflow.engine;

import com.easyshell.server.model.entity.WorkflowNodeRun;
import com.easyshell.server.repository.WorkflowNodeRunRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * attempt 分配与终态写入（§3.6 / §3.2.2 第一、二类事务边界）。
 *
 * <p>{@code startAttempt} 与 {@code finishAttempt} 各是<b>独立短事务</b>（REQUIRES_NEW）：
 * 节点执行可能跑几分钟，绝不能让 attempt 行的事务跟着一起挂着。</p>
 *
 * <p><b>抢占竞态（MF-10 / G-2）</b>：nextAttempt = 该 (runId, nodeKey) 已有最大 attempt + 1，
 * 读与 INSERT 之间有竞态，由 {@code uk_node_run(run_id, node_key, attempt)} 兜底。
 * 兜底命中即视为"另一个执行者已接管本节点"：把挡我们的既有行条件 UPDATE 为
 * failed(reason=attempt_conflict) 留痕，再抛 {@link AttemptConflictException} 终止 run——
 * <b>不 INSERT 与唯一键冲突的同一键</b>（v3 N7-f 修正），也不静默重试
 * （否则下波会再次看到 pending → 再次同样 attempt → 无限热循环）。</p>
 */
@Slf4j
@Service
public class AttemptAllocator {

    private final WorkflowNodeRunRepository nodeRunRepository;

    /**
     * 显式事务模板（PROPAGATION_REQUIRES_NEW）而不是 {@code @Transactional}：
     * 调用方是引擎线程池里手动装配的对象图，不保证一定经过 Spring 代理，
     * 用模板把"独立短事务"变成代码里可见的事实，避免注解被静默跳过。
     */
    private final TransactionTemplate transactionTemplate;

    public AttemptAllocator(WorkflowNodeRunRepository nodeRunRepository,
                            PlatformTransactionManager transactionManager) {
        this.nodeRunRepository = nodeRunRepository;
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
        try {
            return transactionTemplate.execute(status -> {
                int next = nodeRunRepository.findMaxAttempt(runId, nodeKey).map(v -> v + 1).orElse(1);
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
            transactionTemplate.execute(tx -> {
                nodeRunRepository.markFailedIfPresent(runId, nodeKey, blocking, "attempt_conflict",
                        LocalDateTime.now());
                return null;
            });
            log.warn("attempt 抢占失败: run={} node={} attempt={}", runId, nodeKey, blocking);
            throw new AttemptConflictException(runId, nodeKey, blocking);
        }
    }

    public int finishAttempt(WorkflowNodeRun row, AttemptResult result) {
        return transactionTemplate.execute(status -> nodeRunRepository.finishAttemptAtomically(
                row.getId(),
                result.status(),
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
                result.cancelDelivered(),
                LocalDateTime.now(),
                result.durationMs()));
    }

    /**
     * 挂起：running -> waiting 并回填 approvalId（§3.8）。
     *
     * @return 1 = 已挂起；0 = 行已不是 running（取消/超时抢先，本次挂起作废）
     */
    public int suspendAttempt(WorkflowNodeRun row, Long approvalId) {
        return transactionTemplate.execute(status ->
                nodeRunRepository.suspendAttemptAtomically(row.getId(), approvalId));
    }

    /** attempt 终态字段集合（避免 15 参方法在调用处散落） */
    public record AttemptResult(String status, String outputJson, String outputSha256, String previewSha256,
                                String rawParams, String artifactIds, String errorMessage, String warningMessage,
                                String agentVersions, String taskId, Long approvalId, boolean cancelDelivered,
                                Long durationMs) {

        public static AttemptResult.Builder builder() {
            return new AttemptResult.Builder();
        }

        public static final class Builder {
            private String status;
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
                return new AttemptResult(status, outputJson, outputSha256, previewSha256, rawParams,
                        artifactIds, errorMessage, warningMessage, agentVersions, taskId, approvalId,
                        cancelDelivered, durationMs);
            }
        }
    }

    private static String newNodeRunId() {
        return "wnr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
