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

    /**
     * 管理员视角的分页（§6.1 GET /page）。{@code status} 为空表示不过滤。
     *
     * <p>{@code keyword} 匹配名称（大小写不敏感，列表页搜索框）、{@code category} 精确匹配
     * （{@code idx_wf_category} 索引列）；两者为空表示不过滤。未知查询参数由 Controller
     * 直接不接收，这里是唯一允许出现的三个过滤维度。</p>
     */
    @Query("select w from Workflow w where (:status is null " +
            "or (:status = 'active' and w.status <> 'archived') " +
            "or w.status = :status) " +
            "and (:keyword is null or lower(w.name) like lower(concat('%', :keyword, '%'))) " +
            "and (:category is null or w.category = :category) " +
            "order by w.createdAt desc")
    Page<Workflow> pageForAdmin(@Param("status") String status, @Param("keyword") String keyword,
                                @Param("category") String category, Pageable pageable);

    /**
     * 非管理员视角的分页：只能看到"自己创建的"或"公开的"（§6.1 GET /page 的归属过滤，
     * 与 {@code WorkflowAccess.requireWorkflowOwner} 的读侧对应物）。
     */
    @Query("select w from Workflow w where (w.visibility = 'public' or w.createdBy = :userId) " +
            "and (:status is null " +
            "or (:status = 'active' and w.status <> 'archived') " +
            "or w.status = :status) " +
            "and (:keyword is null or lower(w.name) like lower(concat('%', :keyword, '%'))) " +
            "and (:category is null or w.category = :category) " +
            "order by w.createdAt desc")
    Page<Workflow> pageForUser(@Param("status") String status, @Param("userId") Long userId,
                               @Param("keyword") String keyword, @Param("category") String category,
                               Pageable pageable);

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
