package com.easyshell.server.repository;

import com.easyshell.server.model.entity.WorkflowNodeRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface WorkflowNodeRunRepository extends JpaRepository<WorkflowNodeRun, String> {

    List<WorkflowNodeRun> findByRunId(String runId);

    List<WorkflowNodeRun> findByRunIdOrderByNodeKeyAscAttemptAsc(String runId);

    List<WorkflowNodeRun> findByTaskId(String taskId);

    Optional<WorkflowNodeRun> findByRunIdAndNodeKeyAndAttempt(String runId, String nodeKey, Integer attempt);

    /**
     * waiting 集合从 node_run 派生（MF-9：run 级不落 waiting_node_key 字段）。
     */
    List<WorkflowNodeRun> findByRunIdAndStatus(String runId, String status);

    List<WorkflowNodeRun> findByRunIdAndStatusIn(String runId, Collection<String> statuses);

    long countByRunIdAndStatus(String runId, String status);

    /**
     * nextAttempt = 该 (runId, nodeKey) 已有最大 attempt（无任何 attempt 时返回空，调用方从 1 开始）。
     * 读与 INSERT 之间有竞态，由 uk_node_run 兜底（§3.6）。
     */
    @Query("select max(n.attempt) from WorkflowNodeRun n where n.runId = :runId and n.nodeKey = :nodeKey")
    Optional<Integer> findMaxAttempt(@Param("runId") String runId, @Param("nodeKey") String nodeKey);

    /**
     * 抢占失败留痕（MF-10 / N7-f）：uk_node_run 冲突时不 INSERT 与唯一键冲突的同一键，
     * 而是把挡我们的既有行条件 UPDATE 为 failed(reason=attempt_conflict)。
     *
     * @return 1 = 既有行已置 failed；0 = 行不存在（防御性分支，调用方只写审计）
     */
    @Modifying(flushAutomatically = true)
    @Query("update WorkflowNodeRun n set n.status = 'failed', n.errorMessage = :reason, " +
            "n.finishedAt = :finishedAt where n.runId = :runId and n.nodeKey = :nodeKey and n.attempt = :attempt")
    int markFailedIfPresent(@Param("runId") String runId,
                            @Param("nodeKey") String nodeKey,
                            @Param("attempt") int attempt,
                            @Param("reason") String reason,
                            @Param("finishedAt") LocalDateTime finishedAt);

    /**
     * 审批唤醒：waiting -> running（§3.8 resume 第二步）。
     *
     * @return 受影响行数：本 run 下被唤醒的 waiting 节点数
     */
    @Modifying(flushAutomatically = true)
    @Query("update WorkflowNodeRun n set n.status = 'running' where n.runId = :runId and n.status = 'waiting'")
    int resumeWaitingNodes(@Param("runId") String runId);

    /**
     * 取消传播（§3.10）：未开始与等待中的节点批量置 cancelled(reason)。
     * 已派发到 agent 的节点不在此列——其 job 由 task 侧幂等收敛。
     *
     * @return 受影响行数
     */
    @Modifying(flushAutomatically = true)
    @Query("update WorkflowNodeRun n set n.status = 'cancelled', n.errorMessage = :reason " +
            "where n.runId = :runId and n.status in ('pending', 'waiting')")
    int cancelUnfinishedNodes(@Param("runId") String runId, @Param("reason") String reason);

    /**
     * 看门狗：按状态扫 attempt 行（僵尸/超时 attempt 的兜底判定）。
     */
    List<WorkflowNodeRun> findByStatus(String status);

    /**
     * finishAttempt（§3.2.2 第二类事务边界）：attempt 行的终态写入。
     *
     * <p>{@code status = 'running'} 是竞争条件：orphan 线程（节点超时后仍在跑的旧执行体）
     * 或重复回传晚到时，只能有第一个线程把 attempt 收尾，后到的按返回值放弃——
     * 与 {@code JobRepository.finalizeJobAtomically} 同一套路，保证同一 attempt 不会被
     * 终态化两次。</p>
     *
     * <p><b>inputJson 必须在这里落（MF-13 / P-1）</b>：执行体在 attempt 行 INSERT 之后才
     * 回填 {@code ctx.nodeRun().setInputJson(...)}，而 {@code startAttempt} 的新行里没有
     * 这个字段；本 UPDATE 若不带上它，{@code workflow_node_run.input_json} 就恒为 NULL，
     * "这个节点当时拿到了什么参数"永远无法审计复现。</p>
     *
     * @return 1 = 终态已写入；0 = 行不存在或已不是 running
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update WorkflowNodeRun n set n.status = :status, n.inputJson = :inputJson, " +
            "n.outputJson = :outputJson, " +
            "n.outputSha256 = :outputSha256, n.previewSha256 = :previewSha256, n.rawParams = :rawParams, " +
            "n.artifactIds = :artifactIds, n.errorMessage = :errorMessage, " +
            "n.warningMessage = :warningMessage, n.agentVersions = :agentVersions, n.taskId = :taskId, " +
            "n.approvalId = :approvalId, n.cancelDelivered = :cancelDelivered, " +
            "n.finishedAt = :finishedAt, n.durationMs = :durationMs " +
            "where n.id = :id and n.status = 'running'")
    int finishAttemptAtomically(@Param("id") String nodeRunId,
                                @Param("status") String status,
                                @Param("inputJson") String inputJson,
                                @Param("outputJson") String outputJson,
                                @Param("outputSha256") String outputSha256,
                                @Param("previewSha256") String previewSha256,
                                @Param("rawParams") String rawParams,
                                @Param("artifactIds") String artifactIds,
                                @Param("errorMessage") String errorMessage,
                                @Param("warningMessage") String warningMessage,
                                @Param("agentVersions") String agentVersions,
                                @Param("taskId") String taskId,
                                @Param("approvalId") Long approvalId,
                                @Param("cancelDelivered") boolean cancelDelivered,
                                @Param("finishedAt") LocalDateTime finishedAt,
                                @Param("durationMs") Long durationMs);

    /**
     * 挂起（§3.8）：attempt 行 running -> waiting，回填 approvalId。
     *
     * @return 1 = 已挂起；0 = 行不存在或已不是 running（被取消/超时抢先）
     */
    @Modifying(flushAutomatically = true)
    @Query("update WorkflowNodeRun n set n.status = 'waiting', n.approvalId = :approvalId " +
            "where n.id = :id and n.status = 'running'")
    int suspendAttemptAtomically(@Param("id") String nodeRunId, @Param("approvalId") Long approvalId);
}
