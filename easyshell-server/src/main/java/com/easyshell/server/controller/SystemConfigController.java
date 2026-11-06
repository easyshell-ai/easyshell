package com.easyshell.server.controller;

import com.easyshell.server.security.AuditUsers;
import com.easyshell.server.ai.config.AiConfigRefreshService;
import com.easyshell.server.common.result.R;
import com.easyshell.server.model.dto.SystemConfigRequest;
import com.easyshell.server.model.vo.SystemConfigVO;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.service.SystemConfigService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/system/config")
@RequiredArgsConstructor
public class SystemConfigController {

    private final SystemConfigService systemConfigService;
    private final AuditLogService auditLogService;
    private final AiConfigRefreshService aiConfigRefreshService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public R<List<SystemConfigVO>> list(@RequestParam(required = false) String group) {
        if (group != null && !group.isEmpty()) {
            return R.ok(systemConfigService.findByGroup(group));
        }
        return R.ok(systemConfigService.findAll());
    }

    @PutMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public R<SystemConfigVO> save(@Valid @RequestBody SystemConfigRequest request, Authentication auth,
                                  HttpServletRequest httpRequest) {
        Long userId = (Long) auth.getPrincipal();
        SystemConfigVO config = systemConfigService.save(request);
        auditLogService.log(userId, AuditUsers.name(auth), "UPDATE_CONFIG", "system_config",
                String.valueOf(config.getId()), request.getConfigKey() + "=" + request.getConfigValue(),
                httpRequest.getRemoteAddr(), "success");
        return R.ok(config);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public R<Void> delete(@PathVariable Long id, Authentication auth, HttpServletRequest httpRequest) {
        Long userId = (Long) auth.getPrincipal();
        systemConfigService.delete(id);
        auditLogService.log(userId, AuditUsers.name(auth), "DELETE_CONFIG", "system_config",
                String.valueOf(id), null, httpRequest.getRemoteAddr(), "success");
        return R.ok();
    }

    @PostMapping("/refresh-ai-cache")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public R<String> refreshAiCache(Authentication auth, HttpServletRequest httpRequest) {
        Long userId = (Long) auth.getPrincipal();
        aiConfigRefreshService.invalidateAllCaches();
        auditLogService.log(userId, AuditUsers.name(auth), "REFRESH_AI_CACHE", "system_config",
                null, "Manual AI cache refresh", httpRequest.getRemoteAddr(), "success");
        return R.ok("AI model caches refreshed successfully");
    }
}
