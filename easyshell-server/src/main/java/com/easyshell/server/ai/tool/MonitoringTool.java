package com.easyshell.server.ai.tool;

import com.easyshell.server.model.entity.Agent;
import com.easyshell.server.model.entity.MetricSnapshot;
import com.easyshell.server.repository.AgentRepository;
import com.easyshell.server.repository.MetricSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class MonitoringTool {

    private final AgentRepository agentRepository;
    private final MetricSnapshotRepository metricSnapshotRepository;

    @Tool(description = "获取平台总览统计数据，包括主机总数、在线/离线数量、平均 CPU 和内存使用率等")
    public String getDashboardStats() {
        List<Agent> allAgents = agentRepository.findAll();
        if (allAgents.isEmpty()) {
            return "当前没有注册任何主机";
        }

        long total = allAgents.size();
        long online = allAgents.stream().filter(a -> a.getStatus() == 1).count();
        long offline = allAgents.stream().filter(a -> a.getStatus() == 0).count();
        long unstable = allAgents.stream().filter(a -> a.getStatus() == 2).count();

        double avgCpu = allAgents.stream()
                .filter(a -> a.getCpuUsage() != null)
                .mapToDouble(Agent::getCpuUsage)
                .average().orElse(0);

        double avgMem = allAgents.stream()
                .filter(a -> a.getMemUsage() != null)
                .mapToDouble(Agent::getMemUsage)
                .average().orElse(0);

        double avgDisk = allAgents.stream()
                .filter(a -> a.getDiskUsage() != null)
                .mapToDouble(Agent::getDiskUsage)
                .average().orElse(0);

        // Find hosts with high usage
        List<Agent> highCpuAgents = allAgents.stream()
                .filter(a -> a.getCpuUsage() != null && a.getCpuUsage() > 80)
                .toList();

        List<Agent> highMemAgents = allAgents.stream()
                .filter(a -> a.getMemUsage() != null && a.getMemUsage() > 80)
                .toList();

        List<Agent> highDiskAgents = allAgents.stream()
                .filter(a -> a.getDiskUsage() != null && a.getDiskUsage() > 80)
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("平台总览:\n主机总数: %d\n在线: %d | 离线: %d | 不稳定: %d\n",
                total, online, offline, unstable));
        sb.append(String.format("平均 CPU: %.1f%% | 平均内存: %.1f%% | 平均磁盘: %.1f%%\n\n",
                avgCpu, avgMem, avgDisk));

        if (!highCpuAgents.isEmpty()) {
            sb.append("⚠ CPU 使用率超过 80% 的主机:\n");
            for (Agent a : highCpuAgents) {
                sb.append(String.format("  - %s (%s): CPU %.1f%%\n", a.getHostname(), a.getIp(), a.getCpuUsage()));
            }
        }
        if (!highMemAgents.isEmpty()) {
            sb.append("⚠ 内存使用率超过 80% 的主机:\n");
            for (Agent a : highMemAgents) {
                sb.append(String.format("  - %s (%s): 内存 %.1f%%\n", a.getHostname(), a.getIp(), a.getMemUsage()));
            }
        }
        if (!highDiskAgents.isEmpty()) {
            sb.append("⚠ 磁盘使用率超过 80% 的主机:\n");
            for (Agent a : highDiskAgents) {
                sb.append(String.format("  - %s (%s): 磁盘 %.1f%%\n", a.getHostname(), a.getIp(), a.getDiskUsage()));
            }
        }

        return sb.toString();
    }

    @Tool(description = "获取指定主机的历史监控指标，包括 CPU、内存、磁盘使用率的时间序列数据")
    public String getHostMetrics(
            @ToolParam(description = "主机 Agent ID") String agentId,
            @ToolParam(description = "查询最近多少小时的数据，默认 1 小时") int hours) {
        final int h = (hours <= 0) ? 1 : Math.min(hours, 168);

        LocalDateTime end = LocalDateTime.now();
        LocalDateTime start = end.minusHours(h);

        List<MetricSnapshot> metrics = metricSnapshotRepository
                .findByAgentIdAndRecordedAtBetweenOrderByRecordedAtAsc(agentId, start, end);

        if (metrics.isEmpty()) {
            return agentRepository.findById(agentId)
                    .map(a -> String.format("主机 %s (%s) 在最近 %d 小时内无监控数据。\n当前状态: %s, CPU: %s, 内存: %s, 磁盘: %s",
                            a.getHostname(), a.getIp(), h,
                            a.getStatus() == 1 ? "在线" : "离线",
                            a.getCpuUsage() != null ? String.format("%.1f%%", a.getCpuUsage()) : "N/A",
                            a.getMemUsage() != null ? String.format("%.1f%%", a.getMemUsage()) : "N/A",
                            a.getDiskUsage() != null ? String.format("%.1f%%", a.getDiskUsage()) : "N/A"))
                    .orElse("未找到 Agent ID 为 " + agentId + " 的主机");
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("主机 %s 最近 %d 小时监控数据 (%d 条记录):\n\n",
                agentId, h, metrics.size()));

        sb.append("时间 | CPU | 内存 | 磁盘\n");
        sb.append("--- | --- | --- | ---\n");

        // Sample at most 30 data points for readability
        int step = Math.max(1, metrics.size() / 30);
        for (int i = 0; i < metrics.size(); i += step) {
            MetricSnapshot m = metrics.get(i);
            sb.append(String.format("%s | %.1f%% | %.1f%% | %.1f%%\n",
                    m.getRecordedAt(),
                    m.getCpuUsage() != null ? m.getCpuUsage() : 0,
                    m.getMemUsage() != null ? m.getMemUsage() : 0,
                    m.getDiskUsage() != null ? m.getDiskUsage() : 0));
        }

        return sb.toString();
    }
}
