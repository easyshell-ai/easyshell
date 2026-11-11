package com.easyshell.server.repository;

import com.easyshell.server.model.entity.WorkflowNodeRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface WorkflowNodeRunRepository extends JpaRepository<WorkflowNodeRun, String> {

    List<WorkflowNodeRun> findByRunId(String runId);

    List<WorkflowNodeRun> findByRunIdOrderByNodeKeyAscAttemptAsc(String runId);

    List<WorkflowNodeRun> findByTaskId(String taskId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from WorkflowNodeRun n where n.id = :id and n.runId = :runId " +
            "and n.nodeKey = :nodeKey and n.attempt = :attempt and n.status = 'running'")
    Optional<WorkflowNodeRun> lockRunningAttempt(@Param("id") String id, @Param("runId") String runId,
                                                @Param("nodeKey") String nodeKey, @Param("attempt") Integer attempt);

    @Modifying(flushAutomatically = true)
    @Query("update WorkflowNodeRun n set n.taskId = :taskId where n.id = :id and n.status = 'running' and n.taskId is null")
    int bindTask(@Param("id") String id, @Param("taskId") String taskId);

    Optional<WorkflowNodeRun> findByRunIdAndNodeKeyAndAttempt(String runId, String nodeKey, Integer attempt);

    Optional<WorkflowNodeRun> findFirstByRunIdAndNodeKeyOrderByAttemptDesc(String runId, String nodeKey);

    /**
     * waiting 集合从 node_run 派生（MF-9：run 级不落 waiting_node_key 字段）。
     */
    List<WorkflowNodeRun> findByRunIdAndStatus(String runId, String status);

    /** Bounded discovery only; the engine must revalidate ownership under the run lock. */
    @Query("select distinct n.runId from WorkflowNodeRun n where n.status = 'waiting' " +
            "and (:after is null or n.runId > :after) order by n.runId")
    List<String> findWaitingRunIdsAfter(@Param("after") String after,
                                      org.springframework.data.domain.Pageable pageable);

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
            "n.finishedAt = :finishedAt where n.runId = :runId and n.nodeKey = :nodeKey and n.attempt = :attempt and n.status = 'running'")
    int markFailedIfPresent(@Param("runId") String runId,
                            @Param("nodeKey") String nodeKey,
                            @Param("attempt") int attempt,
                            @Param("reason") String reason,
                            @Param("finishedAt") LocalDateTime finishedAt);

    /** 仅唤醒指定 attempt，且在更新时重新验证其审批绑定及决策。 */
    @Modifying(flushAutomatically = true)
    @Query(value = "update workflow_node_run n set status = 'running' where n.id = :id " +
            "and n.run_id = :runId and n.status = 'waiting' and n.approval_id = :approvalId " +
            // GROUP BY prevents MySQL from merging the derived table back into the UPDATE target (1093).
            "and not exists (select other.id from (select id, run_id, node_key, attempt, status " +
            "from workflow_node_run where run_id = :runId group by id, run_id, node_key, attempt, status) other where other.run_id = n.run_id " +
            "and other.node_key = n.node_key and other.id <> n.id " +
            "and (other.status = 'waiting' or other.attempt > n.attempt)) " +
            "and exists (select a.id from workflow_approval a where a.id = n.approval_id " +
            "and a.run_id = n.run_id and a.node_key = n.node_key and a.node_type = n.node_type " +
            "and a.status in ('approved', 'rejected', 'timeout', 'cancelled'))", nativeQuery = true)
    int resumeWaitingAttempt(@Param("id") String id, @Param("runId") String runId,
                             @Param("approvalId") Long approvalId);

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

    /** 迟到或看门狗收尾只更新收尾字段，保留已持久化的审批、任务及其他执行记录。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update WorkflowNodeRun n set n.status = :status, " +
            "n.inputJson = coalesce(n.inputJson, :inputJson), n.taskId = coalesce(n.taskId, :taskId), " +
            "n.errorMessage = :errorMessage, n.finishedAt = :finishedAt, n.durationMs = :durationMs " +
            "where n.id = :id and n.status = 'running'")
    int closeStaleAttempt(@Param("id") String id, @Param("status") String status,
                          @Param("inputJson") String inputJson, @Param("taskId") String taskId,
                          @Param("errorMessage") String errorMessage,
                           @Param("finishedAt") LocalDateTime finishedAt, @Param("durationMs") Long durationMs);

    /** Resume dispatch rejection: close only the exact claimed running attempt. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update WorkflowNodeRun n set n.status = 'cancelled', n.errorMessage = :errorMessage, " +
            "n.finishedAt = :finishedAt, n.durationMs = :durationMs " +
            "where n.id = :id and n.runId = :runId and n.nodeKey = :nodeKey " +
            "and n.attempt = :attempt and n.status = 'running'")
    int closeResumeAbortedAttempt(@Param("id") String id, @Param("runId") String runId,
                                  @Param("nodeKey") String nodeKey, @Param("attempt") Integer attempt,
                                  @Param("errorMessage") String errorMessage,
                                  @Param("finishedAt") LocalDateTime finishedAt,
                                  @Param("durationMs") Long durationMs);

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
            "n.warningMessage = :warningMessage, n.agentVersions = :agentVersions, n.taskId = coalesce(n.taskId, :taskId), " +
            "n.approvalId = case when :preserveApprovalBinding = true then n.approvalId else :approvalId end, " +
            "n.cancelDelivered = :cancelDelivered, " +
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
                                @Param("preserveApprovalBinding") boolean preserveApprovalBinding,
                                @Param("cancelDelivered") boolean cancelDelivered,
                                @Param("finishedAt") LocalDateTime finishedAt,
                                @Param("durationMs") Long durationMs);

    /** 仅挂起尚未绑定任务或审批的指定 script-exec attempt。 */
    @Modifying(flushAutomatically = true)
    @Query("update WorkflowNodeRun n set n.status = 'waiting', n.approvalId = :approvalId " +
            "where n.id = :id and n.runId = :runId and n.nodeKey = :nodeKey " +
            "and n.attempt = :attempt and n.nodeType = 'script-exec' and n.status = 'running' " +
            "and n.taskId is null and n.approvalId is null")
    int suspendDispatchAttempt(@Param("id") String id, @Param("runId") String runId,
                               @Param("nodeKey") String nodeKey, @Param("attempt") int attempt,
                               @Param("approvalId") Long approvalId);

    /** 普通审批专用：只绑定尚未产生副作用的指定 running attempt。 */
    @Modifying(flushAutomatically = true)
    @Query("update WorkflowNodeRun n set n.status = 'waiting', n.approvalId = :approvalId " +
            "where n.id = :id and n.runId = :runId and n.nodeKey = :nodeKey " +
            "and n.attempt = :attempt and n.nodeType = 'approval' and n.status = 'running' " +
            "and n.taskId is null and n.approvalId is null")
    int suspendApprovalAttempt(@Param("id") String id, @Param("runId") String runId,
                               @Param("nodeKey") String nodeKey, @Param("attempt") int attempt,
                               @Param("approvalId") Long approvalId);
}
