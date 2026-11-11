package com.easyshell.server.ai.tool;

import com.easyshell.server.model.entity.Agent;
import com.easyshell.server.model.entity.AgentTag;
import com.easyshell.server.model.entity.Tag;
import com.easyshell.server.repository.AgentRepository;
import com.easyshell.server.repository.AgentTagRepository;
import com.easyshell.server.repository.TagRepository;
import com.easyshell.server.service.HostAclService;
import com.easyshell.server.repository.UserRepository;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class HostTagTool {

    private final AgentRepository agentRepository;
    private final TagRepository tagRepository;
    private final AgentTagRepository agentTagRepository;

    private final HostAclService hostAclService;
    private final UserRepository users;
    private Long currentUserId;

    public HostTagTool(AgentRepository agentRepository, TagRepository tagRepository, AgentTagRepository agentTagRepository, HostAclService hostAclService, UserRepository users) {
        this.agentRepository = agentRepository;
        this.tagRepository = tagRepository;
        this.agentTagRepository = agentTagRepository;
        this.hostAclService = hostAclService;
        this.users = users;
    }

    public HostTagTool forUser(Long userId, String sourceIp) {
        HostTagTool bound = new HostTagTool(agentRepository, tagRepository, agentTagRepository, hostAclService, users);
        bound.currentUserId = userId;
        return bound;
    }

    private boolean canView(String agentId) {
        return currentUserId != null && currentUserId > 0 && hostAclService.allowed(currentUserId, agentId, HostAclService.Permission.VIEW);
    }

    private boolean canManage(String agentId) {
        return canView(agentId) && users.findById(currentUserId)
                .filter(u -> Integer.valueOf(1).equals(u.getStatus()))
                .map(u -> "admin".equals(u.getRole()) || "super_admin".equals(u.getRole())).orElse(false);
    }

    @Tool(description = "查看指定主机的标签")
    public String getHostTags(@ToolParam(description = "目标主机的 Agent ID。如果上下文中用户已指定目标主机则直接使用其 ID，否则请先调用 listHosts 工具获取可用主机列表及其 ID") String agentId) {
        if (!canView(agentId)) return "错误：无权查看该主机。";
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

        if (!canManage(agentId)) return "错误：无权管理该主机。";
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

        if (!canManage(agentId)) return "错误：无权管理该主机。";
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
