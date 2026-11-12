package com.easyshell.server.ai.controller;

import com.easyshell.server.ai.model.entity.HostSoftwareInventory;
import com.easyshell.server.ai.service.HostIntelligenceService;
import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.common.result.R;
import com.easyshell.server.service.HostAclService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/hosts/{agentId}")
@RequiredArgsConstructor
public class HostIntelligenceController {

    private final HostIntelligenceService hostIntelligenceService;
    private final HostAclService hostAclService;

    @PostMapping("/detect")
    public R<String> triggerDetection(@PathVariable String agentId, Authentication auth) {
        Long userId = principal(auth);
        requireAccess(userId, agentId, HostAclService.Permission.EXECUTE);
        String taskId = hostIntelligenceService.triggerDetection(agentId, userId);
        return R.ok(taskId);
    }

    @PostMapping("/detect/{taskId}/parse")
    public R<List<HostSoftwareInventory>> parseDetection(
            @PathVariable String agentId,
            @PathVariable String taskId, Authentication auth) {
        Long actor=principal(auth);
        requireAccess(actor, agentId, HostAclService.Permission.EXECUTE);
        return R.ok(hostIntelligenceService.parseAndStoreAuthorized(taskId,agentId,actor));
    }

    @GetMapping("/software")
    public R<List<HostSoftwareInventory>> getSoftware(@PathVariable String agentId, Authentication auth) {
        requireAccess(principal(auth), agentId, HostAclService.Permission.VIEW);
        return R.ok(hostIntelligenceService.getSoftwareList(agentId));
    }

    @GetMapping("/docker/containers")
    public R<List<HostSoftwareInventory>> getDockerContainers(@PathVariable String agentId, Authentication auth) {
        requireAccess(principal(auth), agentId, HostAclService.Permission.VIEW);
        return R.ok(hostIntelligenceService.getDockerContainers(agentId));
    }

    @GetMapping("/inventory")
    public R<List<HostSoftwareInventory>> getInventory(@PathVariable String agentId, Authentication auth) {
        requireAccess(principal(auth), agentId, HostAclService.Permission.VIEW);
        return R.ok(hostIntelligenceService.getAllInventory(agentId));
    }

    private Long principal(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof Long userId)) {
            throw new BusinessException(401, "Authentication required");
        }
        return userId;
    }

    private void requireAccess(Long userId, String agentId, HostAclService.Permission permission) {
        if (!hostAclService.allowed(userId, agentId, permission)) {
            throw new BusinessException(403, "Access denied");
        }
    }
}
