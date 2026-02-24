package com.easyshell.server.service;

import com.easyshell.server.model.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AuditLogService {

    void log(Long userId, String username, String action, String resourceType,
             String resourceId, String detail, String ip, String result);

    Page<AuditLog> findAll(Pageable pageable);

    Page<AuditLog> findByUserId(Long userId, Pageable pageable);

    Page<AuditLog> findByResourceType(String resourceType, Pageable pageable);

    Page<AuditLog> findByAction(String action, Pageable pageable);
}
