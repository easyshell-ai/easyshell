package com.easyshell.server.service;

import com.easyshell.server.model.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AuditLogService {

    void log(Long userId, String username, String action, String resourceType,
             String resourceId, String detail, String ip, String result);

    /**
     * 同步审计：写失败即使业务失败（不换线程、不吞异常）。
     * 用于被豁免的派发等高危事件留痕——授权与证据必须可追查，
     * 审计丢失时派发本身也必须失败，不能出现"静默豁免"。
     */
    void logSync(Long userId, String username, String action, String resourceType,
                 String resourceId, String detail, String ip, String result);

    Page<AuditLog> findAll(Pageable pageable);

    Page<AuditLog> findByUserId(Long userId, Pageable pageable);

    Page<AuditLog> findByResourceType(String resourceType, Pageable pageable);

    Page<AuditLog> findByAction(String action, Pageable pageable);
}
