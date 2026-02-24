package com.easyshell.server.repository;

import com.easyshell.server.model.entity.AgentTag;
import com.easyshell.server.model.entity.AgentTagId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AgentTagRepository extends JpaRepository<AgentTag, AgentTagId> {

    List<AgentTag> findByAgentId(String agentId);

    List<AgentTag> findByTagId(Long tagId);

    void deleteByAgentIdAndTagId(String agentId, Long tagId);

    boolean existsByAgentIdAndTagId(String agentId, Long tagId);

    long countByTagId(Long tagId);
}
