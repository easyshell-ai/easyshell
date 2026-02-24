package com.easyshell.server.controller;

import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.repository.AgentTagRepository;
import com.easyshell.server.repository.HostCredentialRepository;
import org.springframework.transaction.annotation.Transactional;

import com.easyshell.server.common.result.R;
import com.easyshell.server.model.entity.Agent;
import com.easyshell.server.model.entity.MetricSnapshot;
import com.easyshell.server.model.entity.Task;
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

import java.time.LocalDate;
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

    @GetMapping("/list")
    public R<List<Agent>> list() {
        return R.ok(agentService.findAll());
    }

    @GetMapping("/{agentId}")
    public R<Agent> detail(@PathVariable String agentId) {
        return agentService.findById(agentId)
                .map(R::ok)
                .orElse(R.fail(404, "Agent not found"));
    }


    @DeleteMapping("/{agentId}")
    @Transactional
    public R<Void> deleteHost(@PathVariable String agentId) {
        Agent agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new BusinessException(404, "Agent not found: " + agentId));
        agentTagRepository.findByAgentId(agentId).forEach(agentTagRepository::delete);
        metricSnapshotRepository.deleteByAgentId(agentId);
        agentRepository.deleteById(agentId);
        credentialRepository.findByIp(agent.getIp()).ifPresent(credentialRepository::delete);
        return R.ok();
    }
    @GetMapping("/{agentId}/tags")
    public R<List<TagVO>> agentTags(@PathVariable String agentId) {
        return R.ok(tagService.getAgentTags(agentId));
    }

    @GetMapping("/{agentId}/metrics")
    public R<List<MetricSnapshot>> getMetrics(
            @PathVariable String agentId,
            @RequestParam(defaultValue = "1h") String range) {
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
    public R<DashboardStatsVO> dashboardStats() {
        long total = agentService.countTotal();
        long online = agentService.countOnline();
        long unstable = agentRepository.countByStatus(2);

        List<Agent> onlineAgents = agentRepository.findByStatusOrderByLastHeartbeatDesc(1);

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

        List<Task> recentTasks = taskRepository.findTop10ByOrderByCreatedAtDesc();

        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        long totalTasks = taskRepository.count();
        long todayTasks = taskRepository.countByCreatedAtAfter(todayStart);
        long todaySuccess = taskRepository.countByStatusAndCreatedAtAfter(2, todayStart);
        long todayFailed = taskRepository.countByStatusAndCreatedAtAfter(4, todayStart);
        long successTotal = taskRepository.countByStatus(2);
        Double successRate = totalTasks > 0 ? (successTotal * 100.0 / totalTasks) : null;

        DashboardStatsVO stats = DashboardStatsVO.builder()
                .totalAgents(total)
                .onlineAgents(online)
                .offlineAgents(total - online - unstable)
                .unstableAgents(unstable)
                .totalScripts(scriptRepository.count())
                .totalTasks(totalTasks)
                .todayTasks(todayTasks)
                .todaySuccessTasks(todaySuccess)
                .todayFailedTasks(todayFailed)
                .taskSuccessRate(successRate)
                .avgCpuUsage(avgCpu)
                .avgMemUsage(avgMem)
                .avgDiskUsage(avgDisk)
                .highCpuAgents(highCpu)
                .highMemAgents(highMem)
                .highDiskAgents(highDisk)
                .recentTasks(recentTasks)
                .onlineAgentDetails(onlineAgentDetails)
                .build();
        return R.ok(stats);
    }
}
