package com.easyshell.server.controller;

import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.repository.AgentTagRepository;
import com.easyshell.server.repository.HostCredentialRepository;
import com.easyshell.server.repository.UserRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import com.easyshell.server.service.HostProvisionService;
import com.easyshell.server.service.HostIdentityService;
import com.easyshell.server.service.HostAclService;
import org.springframework.security.core.Authentication;

import com.easyshell.server.common.result.R;
import com.easyshell.server.model.entity.Agent;
import com.easyshell.server.model.entity.MetricSnapshot;
import com.easyshell.server.model.vo.AgentBriefVO;
import com.easyshell.server.model.vo.DashboardStatsVO;
import com.easyshell.server.repository.AgentRepository;
import com.easyshell.server.repository.MetricSnapshotRepository;
import com.easyshell.server.repository.ScriptRepository;
import com.easyshell.server.repository.TaskRepository;
import com.easyshell.server.service.AgentService;
import com.easyshell.server.service.TagService;
import com.easyshell.server.model.vo.TagVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/host")
@RequiredArgsConstructor
public class HostController {

    private final AgentService agentService;
    private final ScriptRepository scriptRepository;
    private final TaskRepository taskRepository;
    private final AgentRepository agentRepository;
    private final TagService tagService;
    private final MetricSnapshotRepository metricSnapshotRepository;

    private final AgentTagRepository agentTagRepository;
    private final HostCredentialRepository credentialRepository;
    private final HostProvisionService hostProvisionService;
    private final HostIdentityService hostIdentityService;
    private final HostAclService hostAclService;
    private final UserRepository users;


    @GetMapping("/list")
    public R<List<Agent>> list(Authentication authentication) {
        return R.ok(visibleAgents(principal(authentication)));
    }

    @GetMapping("/{agentId}")
    public R<Agent> detail(@PathVariable String agentId, Authentication authentication) {
        requireView(principal(authentication), agentId);
        return agentService.findById(agentId)
                .map(R::ok)
                .orElse(R.fail(404, "Agent not found"));
    }


    @DeleteMapping("/{agentId}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public R<Void> deleteHost(@PathVariable String agentId, Authentication authentication) {
        var user = users.findById(principal(authentication)).orElse(null);
        if (user == null || !Integer.valueOf(1).equals(user.getStatus()) || !"super_admin".equals(user.getRole()))
            throw new BusinessException(403, "Super administrator required");
        HostIdentityService.Identity identity = hostIdentityService.lock(agentId);
        Agent agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new BusinessException(404, "Agent not found: " + agentId));
        agentTagRepository.findByAgentId(agentId).forEach(agentTagRepository::delete);
        metricSnapshotRepository.deleteByAgentId(agentId);
        agentRepository.deleteById(agentId);
        hostIdentityService.retire(identity);
        // Credentials are deliberately retained: IP is not proof of host identity.
        return R.ok();
    }

    /**
     * Delete a pending/failed host credential (before agent deployment).
     * This is different from deleteHost which deletes an agent.
     */
    @DeleteMapping("/credential/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public R<Void> deleteCredential(@PathVariable Long id, Authentication authentication) {
        var user = users.findById(principal(authentication)).orElse(null);
        if (user == null || !Integer.valueOf(1).equals(user.getStatus()) || !"super_admin".equals(user.getRole()))
            throw new BusinessException(403, "Super administrator required");
        hostProvisionService.deleteById(id);
        return R.ok();
    }
    @GetMapping("/{agentId}/tags")
    public R<List<TagVO>> agentTags(@PathVariable String agentId, Authentication authentication) {
        requireView(principal(authentication), agentId);
        return R.ok(tagService.getAgentTags(agentId));
    }

    @GetMapping("/{agentId}/metrics")
    public R<List<MetricSnapshot>> getMetrics(
            @PathVariable String agentId,
            @RequestParam(defaultValue = "1h") String range, Authentication authentication) {
        requireView(principal(authentication), agentId);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start = switch (range) {
            case "6h" -> now.minusHours(6);
            case "24h" -> now.minusHours(24);
            case "7d" -> now.minusDays(7);
            case "30d" -> now.minusDays(30);
            default -> now.minusHours(1);
        };
        return R.ok(metricSnapshotRepository.findByAgentIdAndRecordedAtBetweenOrderByRecordedAtAsc(agentId, start, now));
    }

    @GetMapping("/dashboard/stats")
    public R<DashboardStatsVO> dashboardStats(Authentication authentication) {
        List<Agent> visible = visibleAgents(principal(authentication));
        long total = visible.size();
        long online = visible.stream().filter(a -> Integer.valueOf(1).equals(a.getStatus())).count();
        long unstable = visible.stream().filter(a -> Integer.valueOf(2).equals(a.getStatus())).count();

        List<Agent> onlineAgents = visible.stream().filter(a -> Integer.valueOf(1).equals(a.getStatus()))
                .sorted(java.util.Comparator.comparing(Agent::getLastHeartbeat,
                        java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                .toList();

        Double avgCpu = null;
        Double avgMem = null;
        Double avgDisk = null;
        long highCpu = 0;
        long highMem = 0;
        long highDisk = 0;
        if (!onlineAgents.isEmpty()) {
            avgCpu = onlineAgents.stream()
                    .filter(a -> a.getCpuUsage() != null)
                    .mapToDouble(Agent::getCpuUsage).average().orElse(0);
            avgMem = onlineAgents.stream()
                    .filter(a -> a.getMemUsage() != null)
                    .mapToDouble(Agent::getMemUsage).average().orElse(0);
            avgDisk = onlineAgents.stream()
                    .filter(a -> a.getDiskUsage() != null)
                    .mapToDouble(Agent::getDiskUsage).average().orElse(0);
            highCpu = onlineAgents.stream()
                    .filter(a -> a.getCpuUsage() != null && a.getCpuUsage() > 80).count();
            highMem = onlineAgents.stream()
                    .filter(a -> a.getMemUsage() != null && a.getMemUsage() > 80).count();
            highDisk = onlineAgents.stream()
                    .filter(a -> a.getDiskUsage() != null && a.getDiskUsage() > 90).count();
        }

        List<AgentBriefVO> onlineAgentDetails = onlineAgents.stream()
                .map(a -> AgentBriefVO.builder()
                        .id(a.getId())
                        .hostname(a.getHostname())
                        .ip(a.getIp())
                        .cpuUsage(a.getCpuUsage())
                        .memUsage(a.getMemUsage())
                        .diskUsage(a.getDiskUsage())
                        .lastHeartbeat(a.getLastHeartbeat())
                        .build())
                .collect(Collectors.toList());

        // Neither task data nor global script counts are safely scoped to this principal.

        DashboardStatsVO stats = DashboardStatsVO.builder()
                .totalAgents(total)
                .onlineAgents(online)
                .offlineAgents(total - online - unstable)
                .unstableAgents(unstable)
                .scriptsAvailable(false)
                .totalScripts(null)
                .tasksAvailable(false)
                .totalTasks(null)
                .todayTasks(null)
                .todaySuccessTasks(null)
                .todayFailedTasks(null)
                .taskSuccessRate(null)
                .avgCpuUsage(avgCpu)
                .avgMemUsage(avgMem)
                .avgDiskUsage(avgDisk)
                .highCpuAgents(highCpu)
                .highMemAgents(highMem)
                .highDiskAgents(highDisk)
                .recentTasks(null)
                .onlineAgentDetails(onlineAgentDetails)
                .build();
        return R.ok(stats);
    }

    private Long principal(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof Long userId)) {
            throw new BusinessException(401, "Authentication required");
        }
        return userId;
    }

    private void requireView(Long userId, String agentId) {
        if (!hostAclService.allowed(userId, agentId, HostAclService.Permission.VIEW)) {
            throw new BusinessException(403, "Access denied");
        }
    }

    private List<Agent> visibleAgents(Long userId) {
        return agentService.findAll().stream()
                .filter(agent -> hostAclService.allowed(userId, agent.getId(), HostAclService.Permission.VIEW))
                .toList();
    }
}
