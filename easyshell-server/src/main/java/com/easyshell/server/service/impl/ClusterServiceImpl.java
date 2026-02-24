package com.easyshell.server.service.impl;

import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.dto.ClusterRequest;
import com.easyshell.server.model.entity.Agent;
import com.easyshell.server.model.entity.Cluster;
import com.easyshell.server.model.entity.ClusterAgent;
import com.easyshell.server.model.vo.ClusterDetailVO;
import com.easyshell.server.model.vo.ClusterVO;
import com.easyshell.server.repository.AgentRepository;
import com.easyshell.server.repository.ClusterAgentRepository;
import com.easyshell.server.repository.ClusterRepository;
import com.easyshell.server.service.ClusterService;
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
public class ClusterServiceImpl implements ClusterService {

    private final ClusterRepository clusterRepository;
    private final ClusterAgentRepository clusterAgentRepository;
    private final AgentRepository agentRepository;

    @Override
    @Transactional
    public ClusterVO create(ClusterRequest request, Long userId) {
        if (clusterRepository.existsByName(request.getName())) {
            throw new BusinessException(400, "Cluster name already exists: " + request.getName());
        }

        Cluster cluster = new Cluster();
        cluster.setName(request.getName());
        cluster.setDescription(request.getDescription());
        cluster.setCreatedBy(userId);
        cluster = clusterRepository.save(cluster);

        return toVO(cluster, 0);
    }

    @Override
    @Transactional
    public ClusterVO update(Long id, ClusterRequest request) {
        Cluster cluster = clusterRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "Cluster not found"));

        if (!cluster.getName().equals(request.getName()) && clusterRepository.existsByName(request.getName())) {
            throw new BusinessException(400, "Cluster name already exists: " + request.getName());
        }

        cluster.setName(request.getName());
        cluster.setDescription(request.getDescription());
        cluster = clusterRepository.save(cluster);

        int agentCount = (int) clusterAgentRepository.countByClusterId(id);
        return toVO(cluster, agentCount);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        if (!clusterRepository.existsById(id)) {
            throw new BusinessException(404, "Cluster not found");
        }
        clusterAgentRepository.deleteByClusterId(id);
        clusterRepository.deleteById(id);
    }

    @Override
    public List<ClusterVO> findAll() {
        return clusterRepository.findAll().stream()
                .map(c -> toVO(c, (int) clusterAgentRepository.countByClusterId(c.getId())))
                .collect(Collectors.toList());
    }

    @Override
    public ClusterDetailVO getDetail(Long id) {
        Cluster cluster = clusterRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "Cluster not found"));

        List<Agent> agents = getClusterAgents(id);

        return ClusterDetailVO.builder()
                .id(cluster.getId())
                .name(cluster.getName())
                .description(cluster.getDescription())
                .createdBy(cluster.getCreatedBy())
                .agents(agents)
                .build();
    }

    @Override
    @Transactional
    public void addAgents(Long clusterId, List<String> agentIds) {
        if (!clusterRepository.existsById(clusterId)) {
            throw new BusinessException(404, "Cluster not found");
        }

        for (String agentId : agentIds) {
            if (!agentRepository.existsById(agentId)) {
                log.warn("Agent not found, skipping: {}", agentId);
                continue;
            }
            if (clusterAgentRepository.existsByClusterIdAndAgentId(clusterId, agentId)) {
                continue;
            }
            ClusterAgent ca = new ClusterAgent();
            ca.setClusterId(clusterId);
            ca.setAgentId(agentId);
            clusterAgentRepository.save(ca);
        }
    }

    @Override
    @Transactional
    public void removeAgent(Long clusterId, String agentId) {
        clusterAgentRepository.deleteByClusterIdAndAgentId(clusterId, agentId);
    }

    @Override
    public List<Agent> getClusterAgents(Long clusterId) {
        List<ClusterAgent> cas = clusterAgentRepository.findByClusterId(clusterId);
        List<String> agentIds = cas.stream().map(ClusterAgent::getAgentId).collect(Collectors.toList());
        if (agentIds.isEmpty()) return List.of();
        return agentRepository.findAllById(agentIds);
    }

    @Override
    public List<String> getAgentIdsByClusterIds(List<Long> clusterIds) {
        List<String> agentIds = new ArrayList<>();
        for (Long clusterId : clusterIds) {
            clusterAgentRepository.findByClusterId(clusterId)
                    .forEach(ca -> agentIds.add(ca.getAgentId()));
        }
        return agentIds.stream().distinct().collect(Collectors.toList());
    }

    private ClusterVO toVO(Cluster cluster, int agentCount) {
        return ClusterVO.builder()
                .id(cluster.getId())
                .name(cluster.getName())
                .description(cluster.getDescription())
                .agentCount(agentCount)
                .createdBy(cluster.getCreatedBy())
                .createdAt(cluster.getCreatedAt())
                .build();
    }
}
