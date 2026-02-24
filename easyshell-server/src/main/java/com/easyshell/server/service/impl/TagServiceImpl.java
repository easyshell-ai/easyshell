package com.easyshell.server.service.impl;

import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.dto.TagRequest;
import com.easyshell.server.model.entity.AgentTag;
import com.easyshell.server.model.entity.Tag;
import com.easyshell.server.model.vo.TagVO;
import com.easyshell.server.repository.AgentRepository;
import com.easyshell.server.repository.AgentTagRepository;
import com.easyshell.server.repository.TagRepository;
import com.easyshell.server.service.TagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TagServiceImpl implements TagService {

    private final TagRepository tagRepository;
    private final AgentTagRepository agentTagRepository;
    private final AgentRepository agentRepository;

    @Override
    @Transactional
    public TagVO create(TagRequest request) {
        if (tagRepository.existsByName(request.getName())) {
            throw new BusinessException(400, "Tag name already exists: " + request.getName());
        }

        Tag tag = new Tag();
        tag.setName(request.getName());
        tag.setColor(request.getColor());
        tag = tagRepository.save(tag);

        return toVO(tag, 0);
    }

    @Override
    @Transactional
    public TagVO update(Long id, TagRequest request) {
        Tag tag = tagRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "Tag not found"));

        if (!tag.getName().equals(request.getName()) && tagRepository.existsByName(request.getName())) {
            throw new BusinessException(400, "Tag name already exists: " + request.getName());
        }

        tag.setName(request.getName());
        tag.setColor(request.getColor());
        tag = tagRepository.save(tag);

        int agentCount = (int) agentTagRepository.countByTagId(id);
        return toVO(tag, agentCount);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        if (!tagRepository.existsById(id)) {
            throw new BusinessException(404, "Tag not found");
        }
        List<AgentTag> agentTags = agentTagRepository.findByTagId(id);
        agentTagRepository.deleteAll(agentTags);
        tagRepository.deleteById(id);
    }

    @Override
    public List<TagVO> findAll() {
        return tagRepository.findAll().stream()
                .map(t -> toVO(t, (int) agentTagRepository.countByTagId(t.getId())))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void addTagToAgent(Long tagId, String agentId) {
        if (!tagRepository.existsById(tagId)) {
            throw new BusinessException(404, "Tag not found");
        }
        if (!agentRepository.existsById(agentId)) {
            throw new BusinessException(404, "Agent not found");
        }
        if (agentTagRepository.existsByAgentIdAndTagId(agentId, tagId)) {
            return;
        }
        AgentTag at = new AgentTag();
        at.setAgentId(agentId);
        at.setTagId(tagId);
        agentTagRepository.save(at);
    }

    @Override
    @Transactional
    public void removeTagFromAgent(Long tagId, String agentId) {
        agentTagRepository.deleteByAgentIdAndTagId(agentId, tagId);
    }

    @Override
    public List<TagVO> getAgentTags(String agentId) {
        List<AgentTag> agentTags = agentTagRepository.findByAgentId(agentId);
        return agentTags.stream()
                .map(at -> tagRepository.findById(at.getTagId()).orElse(null))
                .filter(t -> t != null)
                .map(t -> toVO(t, (int) agentTagRepository.countByTagId(t.getId())))
                .collect(Collectors.toList());
    }

    @Override
    public List<String> getAgentIdsByTagIds(List<Long> tagIds) {
        List<String> agentIds = new ArrayList<>();
        for (Long tagId : tagIds) {
            agentTagRepository.findByTagId(tagId)
                    .forEach(at -> agentIds.add(at.getAgentId()));
        }
        return agentIds.stream().distinct().collect(Collectors.toList());
    }

    private TagVO toVO(Tag tag, int agentCount) {
        return TagVO.builder()
                .id(tag.getId())
                .name(tag.getName())
                .color(tag.getColor())
                .agentCount(agentCount)
                .build();
    }
}
