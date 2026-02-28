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
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class HostTagTool {

    private final AgentRepository agentRepository;
    private final TagRepository tagRepository;
    private final AgentTagRepository agentTagRepository;

    @Tool(description = "查看指定主机的标签")
    public String getHostTags(@ToolParam(description = "目标主机的 Agent ID。如果上下文中用户已指定目标主机则直接使用其 ID，否则请先调用 listHosts 工具获取可用主机列表及其 ID") String agentId) {
        Agent agent = agentRepository.findById(agentId).orElse(null);
        if (agent == null) {
            return "主机不存在: " + agentId;
        }

        List<AgentTag> agentTags = agentTagRepository.findByAgentId(agentId);
        if (agentTags.isEmpty()) {
            return agent.getHostname() + " 没有任何标签";
        }

        String tagNames = agentTags.stream()
                .map(at -> tagRepository.findById(at.getTagId()).map(Tag::getName).orElse("unknown"))
                .collect(Collectors.joining(", "));

        return agent.getHostname() + " 的标签: " + tagNames;
    }

    @Tool(description = "给主机添加标签，如果标签不存在会自动创建")
    @Transactional
    public String addTagToHost(
            @ToolParam(description = "目标主机的 Agent ID。如果上下文中用户已指定目标主机则直接使用其 ID，否则请先调用 listHosts 工具获取可用主机列表及其 ID") String agentId,
            @ToolParam(description = "标签名称") String tagName) {

        Agent agent = agentRepository.findById(agentId).orElse(null);
        if (agent == null) {
            return "主机不存在: " + agentId;
        }

        Tag tag = tagRepository.findByName(tagName).orElseGet(() -> {
            Tag newTag = new Tag();
            newTag.setName(tagName);
            newTag.setColor("#1890ff");
            return tagRepository.save(newTag);
        });

        if (agentTagRepository.existsByAgentIdAndTagId(agentId, tag.getId())) {
            return agent.getHostname() + " 已有标签: " + tagName;
        }

        AgentTag agentTag = new AgentTag();
        agentTag.setAgentId(agentId);
        agentTag.setTagId(tag.getId());
        agentTagRepository.save(agentTag);

        return "已为 " + agent.getHostname() + " 添加标签: " + tagName;
    }

    @Tool(description = "移除主机的指定标签")
    @Transactional
    public String removeTagFromHost(
            @ToolParam(description = "目标主机的 Agent ID。如果上下文中用户已指定目标主机则直接使用其 ID，否则请先调用 listHosts 工具获取可用主机列表及其 ID") String agentId,
            @ToolParam(description = "标签名称") String tagName) {

        Agent agent = agentRepository.findById(agentId).orElse(null);
        if (agent == null) {
            return "主机不存在: " + agentId;
        }

        Tag tag = tagRepository.findByName(tagName).orElse(null);
        if (tag == null) {
            return "标签不存在: " + tagName;
        }

        if (!agentTagRepository.existsByAgentIdAndTagId(agentId, tag.getId())) {
            return agent.getHostname() + " 没有标签: " + tagName;
        }

        agentTagRepository.deleteByAgentIdAndTagId(agentId, tag.getId());
        return "已从 " + agent.getHostname() + " 移除标签: " + tagName;
    }
}
