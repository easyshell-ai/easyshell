package com.easyshell.server.ai.tool;

import com.easyshell.server.model.entity.Agent;
import com.easyshell.server.model.entity.AgentTag;
import com.easyshell.server.model.entity.Tag;
import com.easyshell.server.repository.AgentRepository;
import com.easyshell.server.repository.AgentTagRepository;
import com.easyshell.server.repository.TagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class HostListTool {

    private final AgentRepository agentRepository;
    private final AgentTagRepository agentTagRepository;
    private final TagRepository tagRepository;

    @Tool(description = "获取所有主机列表，包含主机名、IP、操作系统、状态、CPU/内存/磁盘使用率等信息")
    public String listHosts() {
        List<Agent> agents = agentRepository.findAll();
        if (agents.isEmpty()) {
            return "当前没有已注册的主机";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(agents.size()).append(" 台主机:\n\n");

        for (Agent agent : agents) {
            sb.append("- **").append(agent.getHostname()).append("** (").append(agent.getIp()).append(")\n");
            sb.append("  ID: ").append(agent.getId()).append("\n");
            sb.append("  系统: ").append(agent.getOs()).append(" ").append(agent.getArch()).append("\n");
            sb.append("  状态: ").append(statusText(agent.getStatus())).append("\n");
            if (agent.getCpuUsage() != null) {
                sb.append("  CPU: ").append(String.format("%.1f%%", agent.getCpuUsage())).append("\n");
            }
            if (agent.getMemUsage() != null) {
                sb.append("  内存: ").append(String.format("%.1f%%", agent.getMemUsage())).append("\n");
            }
            if (agent.getDiskUsage() != null) {
                sb.append("  磁盘: ").append(String.format("%.1f%%", agent.getDiskUsage())).append("\n");
            }

            List<AgentTag> agentTags = agentTagRepository.findByAgentId(agent.getId());
            if (!agentTags.isEmpty()) {
                String tagNames = agentTags.stream()
                        .map(at -> tagRepository.findById(at.getTagId()).map(Tag::getName).orElse("unknown"))
                        .collect(Collectors.joining(", "));
                sb.append("  标签: ").append(tagNames).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    @Tool(description = "根据状态筛选主机。status: 0=离线, 1=在线, 2=不稳定")
    public String listHostsByStatus(@ToolParam(description = "主机状态: 0=离线, 1=在线, 2=不稳定") int status) {
        List<Agent> agents = agentRepository.findByStatus(status);
        if (agents.isEmpty()) {
            return "没有找到状态为 " + statusText(status) + " 的主机";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(statusText(status)).append(" 主机共 ").append(agents.size()).append(" 台:\n\n");
        for (Agent agent : agents) {
            sb.append("- ").append(agent.getHostname()).append(" (").append(agent.getIp()).append(") ID: ").append(agent.getId()).append("\n");
        }
        return sb.toString();
    }

    private String statusText(int status) {
        return switch (status) {
            case 0 -> "离线";
            case 1 -> "在线";
            case 2 -> "不稳定";
            default -> "未知";
        };
    }
}
