package com.easyshell.server.repository;

import com.easyshell.server.model.entity.WorkflowVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkflowVersionRepository extends JpaRepository<WorkflowVersion, Long> {

    List<WorkflowVersion> findByWorkflowIdOrderByVersionDesc(Long workflowId);

    Optional<WorkflowVersion> findByWorkflowIdAndVersion(Long workflowId, Integer version);

    Optional<WorkflowVersion> findByIdAndWorkflowId(Long id, Long workflowId);

    Optional<WorkflowVersion> findFirstByWorkflowIdOrderByVersionDesc(Long workflowId);

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
}
