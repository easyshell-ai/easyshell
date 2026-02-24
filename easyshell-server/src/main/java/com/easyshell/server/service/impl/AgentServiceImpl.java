package com.easyshell.server.service.impl;

import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.dto.AgentHeartbeatRequest;
import com.easyshell.server.model.dto.AgentRegisterRequest;
import com.easyshell.server.model.entity.Agent;
import com.easyshell.server.model.entity.MetricSnapshot;
import com.easyshell.server.repository.AgentRepository;
import com.easyshell.server.repository.MetricSnapshotRepository;
import com.easyshell.server.service.AgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentServiceImpl implements AgentService {

    private final AgentRepository agentRepository;
    private final MetricSnapshotRepository metricSnapshotRepository;

    @Override
    @Transactional
    public Agent register(AgentRegisterRequest request, String remoteIp) {
        Agent agent = agentRepository.findById(request.getAgentId()).orElse(null);

        if (agent == null) {
            agent = new Agent();
            agent.setId(request.getAgentId());
            agent.setRegisteredAt(LocalDateTime.now());
            log.info("New agent registered: {} ({})", request.getAgentId(), request.getHostname());
        } else {
            log.info("Agent re-registered: {} ({})", request.getAgentId(), request.getHostname());
        }

        agent.setHostname(request.getHostname());
        agent.setIp(remoteIp);
        agent.setOs(request.getOs());
        agent.setArch(request.getArch());
        agent.setKernel(request.getKernel());
        agent.setCpuModel(request.getCpuModel());
        agent.setCpuCores(request.getCpuCores());
        agent.setMemTotal(request.getMemTotal());
        agent.setAgentVersion(request.getAgentVersion());
        agent.setStatus(1);
        agent.setLastHeartbeat(LocalDateTime.now());

        return agentRepository.save(agent);
    }

    @Override
    @Transactional
    public void heartbeat(AgentHeartbeatRequest request, String remoteIp) {
        Agent agent = agentRepository.findById(request.getAgentId())
                .orElseThrow(() -> new BusinessException(404, "Agent not found: " + request.getAgentId()));

        agent.setStatus(1);
        agent.setIp(remoteIp);
        agent.setLastHeartbeat(LocalDateTime.now());
        agent.setCpuUsage(request.getCpuUsage());
        agent.setMemUsage(request.getMemUsage());
        agent.setDiskUsage(request.getDiskUsage());

        agentRepository.save(agent);

        MetricSnapshot snapshot = new MetricSnapshot();
        snapshot.setAgentId(request.getAgentId());
        snapshot.setCpuUsage(request.getCpuUsage());
        snapshot.setMemUsage(request.getMemUsage());
        snapshot.setDiskUsage(request.getDiskUsage());
        snapshot.setRecordedAt(LocalDateTime.now());
        metricSnapshotRepository.save(snapshot);
    }

    @Override
    public Optional<Agent> findById(String agentId) {
        return agentRepository.findById(agentId);
    }

    @Override
    public List<Agent> findAll() {
        return agentRepository.findAll();
    }

    @Override
    public long countOnline() {
        return agentRepository.countByStatus(1);
    }

    @Override
    public long countTotal() {
        return agentRepository.count();
    }
}
