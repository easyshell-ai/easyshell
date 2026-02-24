package com.easyshell.server.model.vo;

import com.easyshell.server.model.entity.Task;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardStatsVO {

    private long totalAgents;
    private long onlineAgents;
    private long offlineAgents;
    private long totalScripts;
    private long totalTasks;
    private Double avgCpuUsage;
    private Double avgMemUsage;
    private Double avgDiskUsage;
    private List<Task> recentTasks;
    private List<AgentBriefVO> onlineAgentDetails;
    private long unstableAgents;
    private long todayTasks;
    private long todaySuccessTasks;
    private long todayFailedTasks;
    private Double taskSuccessRate;
    private long highCpuAgents;
    private long highMemAgents;
    private long highDiskAgents;
}
