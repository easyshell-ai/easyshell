package com.easyshell.server.repository;

import com.easyshell.server.model.entity.WorkflowVersion;
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
public interface WorkflowVersionRepository extends JpaRepository<WorkflowVersion, Long> {

    List<WorkflowVersion> findByWorkflowIdOrderByVersionDesc(Long workflowId);

    Optional<WorkflowVersion> findByWorkflowIdAndVersion(Long workflowId, Integer version);

    Optional<WorkflowVersion> findByIdAndWorkflowId(Long id, Long workflowId);

    Optional<WorkflowVersion> findFirstByWorkflowIdOrderByVersionDesc(Long workflowId);

    /**
     * 只读投影：{@code [id, version]} 两列（列表页回填 {@code Workflow.currentVersion}）。
     *
     * <p>不返回实体是为了不把 MEDIUMTEXT 的 {@code definition_json} 拉进内存——
     * 一页 20 条工作流就是 20 份完整定义。</p>
     */
    @Query("select v.id, v.version from WorkflowVersion v where v.id in :ids")
    List<Object[]> findIdAndVersionByIdIn(@Param("ids") Collection<Long> ids);

    boolean existsByWorkflowIdAndVersion(Long workflowId, Integer version);

    /**
     * 下一个版本号 = 已有最大 version + 1（无任何版本时从 1 开始）。
     * 读与 INSERT 之间有竞态，由 uk_wv_workflow_version 兜底，调用方捕获
     * DataIntegrityViolationException 后转 409 VERSION_CONFLICT。
     */
    @Query("select coalesce(max(v.version), 0) from WorkflowVersion v where v.workflowId = :workflowId")
    int findMaxVersion(@Param("workflowId") Long workflowId);

    /**
     * 只取定义 JSON 的只读投影（drive() 主循环每次都要反序列化定义，
     * 不拉整行避免把 MEDIUMTEXT 之外的大列也带进一级缓存）。
     */
    @Query("select v.definitionJson from WorkflowVersion v where v.id = :id")
    Optional<String> findDefinition(@Param("id") Long versionId);

    /**
     * 当前草稿行 = 该工作流 version 最大的未发布版本（编辑器的工作副本）。
     */
    Optional<WorkflowVersion> findFirstByWorkflowIdAndIsValidFalseOrderByVersionDesc(Long workflowId);

    /**
     * 草稿保存的<b>乐观锁</b>（§6.1 PUT /{id}/versions/draft）：以 {@code base_version}
     * 为竞争条件更新定义，成功即把令牌 +1（下一个保存必须带回新值）。
     *
     * <p>影响 0 行 = 客户端持有的 baseVersion 已过期（他人在其之后保存过）→
     * 409 VERSION_CONFLICT + 三选一契约。<b>不做自动三方合并。</b></p>
     *
     * @return 1 = 已保存；0 = base_version 不匹配
     */
    @Modifying(flushAutomatically = true)
    @Query("update WorkflowVersion v set v.definitionJson = :definitionJson, " +
            "v.definitionSha256 = :definitionSha256, v.changelog = :changelog, " +
            "v.baseVersion = v.baseVersion + 1 " +
            "where v.id = :id and v.baseVersion = :expectedBaseVersion")
    int saveDraftIfBaseVersionMatches(@Param("id") Long versionId,
                                      @Param("definitionJson") String definitionJson,
                                      @Param("definitionSha256") String definitionSha256,
                                      @Param("changelog") String changelog,
                                      @Param("expectedBaseVersion") int expectedBaseVersion);

    /**
     * 覆盖保存（409 三选一里的 overwrite）：无条件写定义并推进 baseVersion，
     * 同时记 {@code WORKFLOW_OVERWRITE} 审计（调用方负责）。
     *
     * @return 1 = 已覆盖；0 = 草稿行不存在
     */
    @Modifying(flushAutomatically = true)
    @Query("update WorkflowVersion v set v.definitionJson = :definitionJson, " +
            "v.definitionSha256 = :definitionSha256, v.changelog = :changelog, " +
            "v.baseVersion = v.baseVersion + 1 where v.id = :id and v.isValid = false")
    int overwriteDraft(@Param("id") Long versionId,
                       @Param("definitionJson") String definitionJson,
                       @Param("definitionSha256") String definitionSha256,
                       @Param("changelog") String changelog);

    /**
     * 发布：草稿行 is_valid=false -> true，回填 publishedAt/publishedBy/estimatedRisk。
     * 发布后本行<b>不可变</b>（§5.2：definition_sha256 是执行指纹的唯一载体）。
     *
     * @return 1 = 已发布；0 = 行不存在或已发布
     */
    @Modifying(flushAutomatically = true)
    @Query("update WorkflowVersion v set v.isValid = true, v.publishedAt = :publishedAt, " +
            "v.publishedBy = :publishedBy, v.estimatedRisk = :estimatedRisk " +
            "where v.id = :id and v.isValid = false")
    int publishDraftAtomically(@Param("id") Long versionId,
                               @Param("publishedAt") LocalDateTime publishedAt,
                               @Param("publishedBy") Long publishedBy,
                               @Param("estimatedRisk") String estimatedRisk);
}
