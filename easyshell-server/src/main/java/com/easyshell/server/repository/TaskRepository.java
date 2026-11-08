package com.easyshell.server.repository;

import com.easyshell.server.model.entity.Task;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TaskRepository extends JpaRepository<Task, String> {

    List<Task> findByCreatedByOrderByCreatedAtDesc(Long createdBy);

    List<Task> findAllByOrderByCreatedAtDesc();

    List<Task> findByStatus(Integer status);

    List<Task> findTop10ByOrderByCreatedAtDesc();

    Page<Task> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<Task> findByStatusOrderByCreatedAtDesc(Integer status, Pageable pageable);

    /**
     * 归属过滤的分页查询（B2）：非管理员用户只能看到自己创建的任务，
     * 与 GET /task/{taskId} 的 requireAccess 语义保持一致。
     */
    Page<Task> findByCreatedByOrderByCreatedAtDesc(Long createdBy, Pageable pageable);

    Page<Task> findByStatusAndCreatedByOrderByCreatedAtDesc(Integer status, Long createdBy, Pageable pageable);

    long countByCreatedAtAfter(LocalDateTime time);

    long countByStatusAndCreatedAtAfter(Integer status, LocalDateTime time);

    long countByStatus(Integer status);

    List<Task> findByApprovalStatusOrderByCreatedAtDesc(String approvalStatus);

    /**
     * 任务计数（success/failed/total）的只读投影。
     * 走数据库标量查询，绕开一级缓存里可能已过期的 Task 实体，
     * 保证终态判断读到的是原子 UPDATE 之后的最新值。
     */
    interface TaskCountProjection {

        Integer getSuccessCount();

        Integer getFailedCount();

        Integer getTotalCount();
    }

    @Query("select t.successCount as successCount, t.failedCount as failedCount, t.totalCount as totalCount " +
            "from Task t where t.id = :id")
    Optional<TaskCountProjection> findCountsById(@Param("id") String taskId);

    /**
     * 原子累加任务的成功/失败计数。
     * <p>
     * 为什么用条件 UPDATE 而不是"查出 job 统计完再 save(task)"：
     * 多个 agent 几乎同时回传结果时，旧写法是典型的读-改-写竞态——两个线程都读到
     * 旧的 success/failed 计数，后提交的把先提交的覆盖掉，导致计数少计、
     * completed == total 永远不成立，任务永远卡在 running。
     * 单条 UPDATE 让"读旧值 + 加一 + 写回"在数据库内部由行锁串行化完成，
     * 每次回传都能真实落库一次，杜绝丢更新。
     * </p>
     * <p>
     * flushAutomatically = true：先把同一事务内还没刷新的 job/task 变更落库，
     * 否则 createAndDispatch 里 task 行的 INSERT 尚未执行时，本 UPDATE 会命中 0 行。
     * </p>
     *
     * @return 受影响行数：1 = 计数已累加；0 = task 不存在（调用方应放弃后续处理）
     */
    @Modifying(flushAutomatically = true)
    @Query("update Task t set " +
            "t.successCount = t.successCount + case when :status = 2 then 1 else 0 end, " +
            "t.failedCount = t.failedCount + case when :status = 3 or :status = 4 then 1 else 0 end " +
            "where t.id = :id")
    int incrementCountersAtomically(@Param("id") String taskId, @Param("status") int status);

    /**
     * 原子写任务终态：仅当任务仍处于 running(1) 时才生效。
     * <p>
     * 并发回传下多个线程都可能算出"全部 job 已终态"，用 status = 1 作为竞争条件，
     * 按返回值判定谁真正写入了终态（1 = 本线程写入；0 = 已有其他线程写过，幂等跳过），
     * 避免重复写终态、以及后到的计数把已终结状态改写掉。
     * </p>
     *
     * @return 受影响行数：1 = 终态已写入；0 = 任务不存在或已处于终态
     */
    @Modifying(flushAutomatically = true)
    @Query("update Task t set t.status = :status, t.finishedAt = :finishedAt " +
            "where t.id = :id and t.status = 1")
    int finishTaskAtomically(@Param("id") String taskId,
                             @Param("status") int status,
                             @Param("finishedAt") LocalDateTime finishedAt);
}
