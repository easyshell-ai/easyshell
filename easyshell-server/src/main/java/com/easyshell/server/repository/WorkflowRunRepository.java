package com.easyshell.server.repository;

import com.easyshell.server.model.entity.WorkflowRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
public interface WorkflowRunRepository extends JpaRepository<WorkflowRun, String> {

    List<WorkflowRun> findByStatus(String status);

    List<WorkflowRun> findByStatusIn(Collection<String> statuses);

    Page<WorkflowRun> findByWorkflowIdOrderByCreatedAtDesc(Long workflowId, Pageable pageable);

    Page<WorkflowRun> findByStartedByOrderByCreatedAtDesc(Long startedBy, Pageable pageable);

    /**
     * 幂等重查：同 userId 同 idempotency_key 已触发过时返回首个 run，
     * 重复触发直接返回 200 + 首个 runId（§3.5 幂等①）。
     */
    Optional<WorkflowRun> findByStartedByAndIdempotencyKey(Long startedBy, String idempotencyKey);

    /**
     * 全局并发 run 数（§3.9：默认 3，超出的 run 置 queued）。
     */
    long countByStatusIn(Collection<String> statuses);

    /**
     * 看门狗：run 级超时扫描（started_at + timeoutSeconds < now，§3.10）。
     */
    List<WorkflowRun> findByStatusInAndStartedAtBefore(Collection<String> statuses, LocalDateTime startedBefore);

    /**
     * 多实例启动自检（G-20）：发现"其他实例且在 120s 内心跳过"的活跃 run 时拒绝启动。
     */
    List<WorkflowRun> findByOwnerInstanceNotAndHeartbeatAtAfterAndStatusIn(
            String ownerInstance, LocalDateTime heartbeatAfter, Collection<String> statuses);

    /**
     * 原子写 run 终态：仅当当前状态不在终态集合中时才生效。
     * <p>
     * 并发回传/看门狗/取消三方都可能算出"该结束了"，用"当前状态仍是非终态"作为竞争条件，
     * 按返回值判定谁真正写入了终态（1 = 本线程写入；0 = 已有其他线程写过，幂等跳过），
     * 避免重复写终态、以及后到的结果把已终结状态改写掉。
     * </p>
     *
     * @param terminalStatuses 终态集合；传空集合时等价于"任何状态都可写"（Hibernate 把
     *                        {@code not in ()} 编译为永真），调用方应显式传入四个终态
     * @return 受影响行数：1 = 终态已写入；0 = run 不存在或已处于终态
     */
    @Modifying(flushAutomatically = true)
    @Query("update WorkflowRun r set r.status = :status, r.finishedAt = :finishedAt, " +
            "r.durationMs = :durationMs, r.outputJson = :outputJson, r.errorMessage = :errorMessage " +
            "where r.id = :id and r.status not in (:terminalStatuses)")
    int finishRunAtomically(@Param("id") String runId,
                            @Param("status") String status,
                            @Param("finishedAt") LocalDateTime finishedAt,
                            @Param("durationMs") Long durationMs,
                            @Param("outputJson") String outputJson,
                            @Param("errorMessage") String errorMessage,
                            @Param("terminalStatuses") Collection<String> terminalStatuses);

    /**
     * 挂起：running -> waiting，仅当本波全部节点都已终态或 waiting 时才可能影响 1 行
     * （MF-9：同波单 waiting；影响 0 行说明本波还有 running 节点，交给 10s 扫描器二次判定）。
     *
     * @return 1 = 已挂起；0 = run 不存在或已不是 running
     */
    @Modifying(flushAutomatically = true)
    @Query("update WorkflowRun r set r.status = 'waiting' where r.id = :id and r.status = 'running'")
    int suspendIfRunning(@Param("id") String runId);

    /**
     * 审批通过后的唤醒：waiting -> running，影响 0 行即幂等返回（§3.8）。
     *
     * @return 1 = 已唤醒；0 = run 不存在或已不是 waiting
     */
    @Modifying(flushAutomatically = true)
    @Query("update WorkflowRun r set r.status = 'running' where r.id = :id and r.status = 'waiting'")
    int resumeIfWaiting(@Param("id") String runId);

    /**
     * 置位取消请求（§3.10）：仅当尚未置位时生效，返回值用于审计"谁先按的取消"。
     *
     * @return 1 = 本次置位；0 = run 不存在或已有人先置位
     */
    @Modifying(flushAutomatically = true)
    @Query("update WorkflowRun r set r.cancelRequested = true where r.id = :id and r.cancelRequested = false")
    int requestCancelAtomically(@Param("id") String runId);

    /**
     * 波末 checkpoint：心跳 + 当前波节点 + 标量变量全量快照一把写（§3.7）。
     *
     * @return 1 = 已写；0 = run 不存在
     */
    @Modifying(flushAutomatically = true)
    @Query("update WorkflowRun r set r.heartbeatAt = :heartbeatAt, r.currentNodes = :currentNodes, " +
            "r.variablesJson = :variablesJson, r.variablesTruncated = :variablesTruncated where r.id = :id")
    int checkpointAtomically(@Param("id") String runId,
                             @Param("heartbeatAt") LocalDateTime heartbeatAt,
                             @Param("currentNodes") String currentNodes,
                             @Param("variablesJson") String variablesJson,
                             @Param("variablesTruncated") boolean variablesTruncated);
}
