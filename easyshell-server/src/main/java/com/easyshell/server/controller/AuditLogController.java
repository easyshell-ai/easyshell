package com.easyshell.server.controller;

import com.easyshell.server.common.result.R;
import com.easyshell.server.model.entity.AuditLog;
import com.easyshell.server.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/audit")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogService auditLogService;

    @GetMapping("/list")
    public R<Page<AuditLog>> list(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        PageRequest pageRequest = PageRequest.of(page, size);

        if (userId != null) {
            return R.ok(auditLogService.findByUserId(userId, pageRequest));
        }
        if (resourceType != null) {
            return R.ok(auditLogService.findByResourceType(resourceType, pageRequest));
        }
        if (action != null) {
            return R.ok(auditLogService.findByAction(action, pageRequest));
        }
        return R.ok(auditLogService.findAll(pageRequest));
    }
}
