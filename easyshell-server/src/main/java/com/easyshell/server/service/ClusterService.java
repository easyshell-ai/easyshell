package com.easyshell.server.service;

import com.easyshell.server.model.dto.ClusterRequest;
import com.easyshell.server.model.entity.Agent;
import com.easyshell.server.model.vo.ClusterDetailVO;
import com.easyshell.server.model.vo.ClusterVO;

import java.util.List;

public interface ClusterService {

    ClusterVO create(ClusterRequest request, Long userId);

    ClusterVO update(Long id, ClusterRequest request);

    void delete(Long id);

    List<ClusterVO> findAll();

    ClusterDetailVO getDetail(Long id);

    void addAgents(Long clusterId, List<String> agentIds);

    void removeAgent(Long clusterId, String agentId);

    List<Agent> getClusterAgents(Long clusterId);

    List<String> getAgentIdsByClusterIds(List<Long> clusterIds);
}
