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
}
