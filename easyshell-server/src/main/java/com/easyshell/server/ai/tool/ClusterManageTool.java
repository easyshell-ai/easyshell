package com.easyshell.server.ai.tool;

import com.easyshell.server.model.vo.ClusterDetailVO;
import com.easyshell.server.model.vo.ClusterVO;
import com.easyshell.server.service.ClusterService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ClusterManageTool {

    private final ClusterService clusterService;

    @Tool(description = "查询所有集群列表，包括集群名称、描述、包含的主机数量")
    public String listClusters() {
        List<ClusterVO> clusters = clusterService.findAll();
        if (clusters.isEmpty()) {
            return "当前没有任何集群";
        }

        StringBuilder sb = new StringBuilder("集群列表:\n");
        for (ClusterVO c : clusters) {
            sb.append(String.format("- [ID:%d] %s | 主机数: %d | 描述: %s\n",
                    c.getId(),
                    c.getName(),
                    c.getAgentCount(),
                    c.getDescription() != null ? c.getDescription() : "无"));
        }
        return sb.toString();
    }

    @Tool(description = "查询指定集群的详细信息，包括集群内的所有主机")
    public String getClusterDetail(@ToolParam(description = "集群 ID") Long id) {
        try {
            ClusterDetailVO detail = clusterService.getDetail(id);
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("集群: %s\nID: %d\n描述: %s\n\n",
                    detail.getName(), detail.getId(),
                    detail.getDescription() != null ? detail.getDescription() : "无"));

            if (detail.getAgents() != null && !detail.getAgents().isEmpty()) {
                sb.append("包含主机:\n");
                for (var agent : detail.getAgents()) {
                    sb.append(String.format("  - %s (%s) | 状态: %s\n",
                            agent.getHostname(), agent.getIp(),
                            agent.getStatus() == 1 ? "在线" : "离线"));
                }
            } else {
                sb.append("集群内暂无主机\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "查询集群详情失败: " + e.getMessage();
        }
    }
}
