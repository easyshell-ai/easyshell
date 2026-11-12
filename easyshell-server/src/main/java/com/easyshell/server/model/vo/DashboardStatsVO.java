package com.easyshell.server.model.vo;

import com.easyshell.server.model.entity.Task;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.ALWAYS)
public class DashboardStatsVO {

    private long totalAgents;
    private long onlineAgents;
    private long offlineAgents;
    private boolean scriptsAvailable;
    private Long totalScripts;
    private boolean tasksAvailable;
    private Long totalTasks;
    private Double avgCpuUsage;
    private Double avgMemUsage;
    private Double avgDiskUsage;
    private List<Task> recentTasks;
    private List<AgentBriefVO> onlineAgentDetails;
    private long unstableAgents;
    private Long todayTasks;
    private Long todaySuccessTasks;
    private Long todayFailedTasks;
    private Double taskSuccessRate;
    private long highCpuAgents;
    private long highMemAgents;
    private long highDiskAgents;
}
