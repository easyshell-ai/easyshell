package com.easyshell.server.ai.controller;

import com.easyshell.server.ai.model.entity.HostSoftwareInventory;
import com.easyshell.server.ai.service.HostIntelligenceService;
import com.easyshell.server.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/hosts/{agentId}")
@RequiredArgsConstructor
public class HostIntelligenceController {

    private final HostIntelligenceService hostIntelligenceService;

    @PostMapping("/detect")
    public R<String> triggerDetection(@PathVariable String agentId, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        String taskId = hostIntelligenceService.triggerDetection(agentId, userId);
        return R.ok(taskId);
    }

    @PostMapping("/detect/{taskId}/parse")
    public R<List<HostSoftwareInventory>> parseDetection(
            @PathVariable String agentId,
            @PathVariable String taskId) {
        List<HostSoftwareInventory> results = hostIntelligenceService.parseAndStore(taskId, agentId);
        return R.ok(results);
    }

    @GetMapping("/software")
    public R<List<HostSoftwareInventory>> getSoftware(@PathVariable String agentId) {
        return R.ok(hostIntelligenceService.getSoftwareList(agentId));
    }

    @GetMapping("/docker/containers")
    public R<List<HostSoftwareInventory>> getDockerContainers(@PathVariable String agentId) {
        return R.ok(hostIntelligenceService.getDockerContainers(agentId));
    }

    @GetMapping("/inventory")
    public R<List<HostSoftwareInventory>> getInventory(@PathVariable String agentId) {
        return R.ok(hostIntelligenceService.getAllInventory(agentId));
    }
}
