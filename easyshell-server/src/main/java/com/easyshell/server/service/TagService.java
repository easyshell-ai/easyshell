package com.easyshell.server.service;

import com.easyshell.server.model.dto.TagRequest;
import com.easyshell.server.model.vo.TagVO;

import java.util.List;

public interface TagService {

    TagVO create(TagRequest request);

    TagVO update(Long id, TagRequest request);

    void delete(Long id);

    List<TagVO> findAll();

    void addTagToAgent(Long tagId, String agentId);

    void removeTagFromAgent(Long tagId, String agentId);

    List<TagVO> getAgentTags(String agentId);

    List<String> getAgentIdsByTagIds(List<Long> tagIds);
}
