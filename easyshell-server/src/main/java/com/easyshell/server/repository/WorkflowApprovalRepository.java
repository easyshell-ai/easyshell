package com.easyshell.server.repository;

import com.easyshell.server.model.entity.WorkflowApproval;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface WorkflowApprovalRepository extends JpaRepository<WorkflowApproval, Long> {

    List<WorkflowApproval> findByRunId(String runId);

    Optional<WorkflowApproval> findByRunIdAndNodeKey(String runId, String nodeKey);

    /**
     * 待我审批：只返回 pending 且未过期的行（§3.8，过期件由看门狗置 timeout）。
     */
    List<WorkflowApproval> findByStatusAndExpiresAtAfter(String status, LocalDateTime now);

    /**
     * 看门狗：审批超时扫描（expires_at 到点，§3.10）。
     */
    List<WorkflowApproval> findByStatusAndExpiresAtBefore(String status, LocalDateTime now);

    /**
     * 决策端点唯一写入口（MF-8：只做状态迁移）。
     * <p>
     * 三重竞争条件：status = 'pending' 防重复决策，version = 期望值做乐观锁，
     * 命中后同一语句内 version + 1。返回 0 行 = 已有人决策过（409 APPROVAL_DECIDED）
     * 或版本已被推进，调用方不得重试写入。
     * </p>
     *
     * @return 1 = 决策已落库；0 = 非 pending 或版本不匹配
     */
    @Modifying(flushAutomatically = true)
    @Query("update WorkflowApproval a set a.status = :status, a.decidedBy = :decidedBy, " +
            "a.decidedAt = :decidedAt, a.decisionComment = :decisionComment, a.version = a.version + 1 " +
            "where a.id = :id and a.status = 'pending' and a.version = :expectedVersion")
    int decideIfPending(@Param("id") Long approvalId,
                        @Param("status") String status,
                        @Param("decidedBy") Long decidedBy,
                        @Param("decidedAt") LocalDateTime decidedAt,
                        @Param("decisionComment") String decisionComment,
                        @Param("expectedVersion") int expectedVersion);

    /**
     * 悬死审批清理（SF-7）：run 进入终态时把该 run 下仍 pending 的审批批量置 cancelled。
     *
     * @return 受影响行数
     */
    @Modifying(flushAutomatically = true)
    @Query("update WorkflowApproval a set a.status = 'cancelled' where a.runId = :runId and a.status = 'pending'")
    int cancelPendingByRunId(@Param("runId") String runId);

    /**
     * 审批超时（§3.10 四级超时之审批级）：expires_at 到点由 WorkflowWatchdog 置 timeout。
     *
     * <p>与 {@link #decideIfPending} 同样带 version 乐观锁，返回 0 行 = 已被人工决策
     * （看门狗与审批端点的竞态由此确定收敛：先到者赢，后到者放弃）。</p>
     *
     * @return 1 = 已置 timeout；0 = 非 pending 或版本不匹配
     */
    @Modifying(flushAutomatically = true)
    @Query("update WorkflowApproval a set a.status = 'timeout' " +
            "where a.id = :id and a.status = 'pending' and a.version = :expectedVersion")
    int expireIfPending(@Param("id") Long approvalId, @Param("expectedVersion") int expectedVersion);
}
