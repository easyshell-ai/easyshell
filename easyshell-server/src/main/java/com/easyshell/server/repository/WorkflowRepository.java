package com.easyshell.server.repository;

import com.easyshell.server.model.entity.Workflow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface WorkflowRepository extends JpaRepository<Workflow, Long> {

    /**
     * 管理员视角的全量分页（按创建时间倒序）。
     */
    Page<Workflow> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * 非管理员视角：只能看到"自己创建的"或"公开的"（§6.1 GET /page 的归属过滤）。
     */
    Page<Workflow> findByVisibilityOrCreatedByOrderByCreatedAtDesc(String visibility, Long createdBy, Pageable pageable);

    Page<Workflow> findByCreatedByOrderByCreatedAtDesc(Long createdBy, Pageable pageable);

    Page<Workflow> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);

    long countByCreatedBy(Long createdBy);

    boolean existsByIdAndCreatedBy(Long id, Long createdBy);

    /**
     * 原子累加运行计数（触发时 +1）。
     * <p>
     * 为什么用条件 UPDATE 而不是"查出 runCount 加一再 save"：多个触发并发时读-改-写会互相覆盖，
     * 单条 UPDATE 让"读旧值 + 加一 + 写回"由数据库行锁串行化完成。
     * </p>
     *
     * @return 受影响行数：1 = 已累加；0 = 工作流不存在
     */
    @Modifying(flushAutomatically = true)
    @Query("update Workflow w set w.runCount = w.runCount + 1, w.lastRunAt = :lastRunAt where w.id = :id")
    int incrementRunCountAtomically(@Param("id") Long workflowId, @Param("lastRunAt") LocalDateTime lastRunAt);

    /**
     * 原子累加成功计数（run 进入 succeeded 终态时 +1）。
     *
     * @return 受影响行数：1 = 已累加；0 = 工作流不存在
     */
    @Modifying(flushAutomatically = true)
    @Query("update Workflow w set w.successCount = w.successCount + 1 where w.id = :id")
    int incrementSuccessCountAtomically(@Param("id") Long workflowId);

    /**
     * 发布成功后回写 current_version_id（软关联 workflow_version.id）。
     *
     * @return 受影响行数：1 = 已回写；0 = 工作流不存在
     */
    @Modifying(flushAutomatically = true)
    @Query("update Workflow w set w.currentVersionId = :versionId where w.id = :id")
    int updateCurrentVersionAtomically(@Param("id") Long workflowId, @Param("versionId") Long versionId);
}
