package com.easyshell.server.repository;

import com.easyshell.server.model.entity.ClusterAgent;
import com.easyshell.server.model.entity.ClusterAgentId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ClusterAgentRepository extends JpaRepository<ClusterAgent, ClusterAgentId> {

    List<ClusterAgent> findByClusterId(Long clusterId);

    List<ClusterAgent> findByAgentId(String agentId);

    void deleteByClusterIdAndAgentId(Long clusterId, String agentId);

    boolean existsByClusterIdAndAgentId(Long clusterId, String agentId);

    long countByClusterId(Long clusterId);

    void deleteByClusterId(Long clusterId);
}
