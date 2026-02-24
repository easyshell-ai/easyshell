package com.easyshell.server.service;

import com.easyshell.server.model.entity.Agent;
import com.easyshell.server.repository.AgentRepository;
import com.easyshell.server.repository.MetricSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentStatusMonitor {

    private static final int HEARTBEAT_TIMEOUT_SECONDS = 90;
    private static final int UNSTABLE_THRESHOLD_SECONDS = 60;

    private final AgentRepository agentRepository;
    private final MetricSnapshotRepository metricSnapshotRepository;

    @Scheduled(fixedRate = 30000)
    public void checkAgentStatus() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime offlineThreshold = now.minusSeconds(HEARTBEAT_TIMEOUT_SECONDS);
        LocalDateTime unstableThreshold = now.minusSeconds(UNSTABLE_THRESHOLD_SECONDS);

        List<Agent> onlineAgents = agentRepository.findByStatus(1);
        for (Agent agent : onlineAgents) {
            if (agent.getLastHeartbeat() == null || agent.getLastHeartbeat().isBefore(offlineThreshold)) {
                agent.setStatus(0);
                agentRepository.save(agent);
                log.warn("Agent {} marked offline (no heartbeat for {}s)", agent.getId(), HEARTBEAT_TIMEOUT_SECONDS);
            } else if (agent.getLastHeartbeat().isBefore(unstableThreshold)) {
                agent.setStatus(2);
                agentRepository.save(agent);
                log.warn("Agent {} marked unstable", agent.getId());
            }
        }

        List<Agent> unstableAgents = agentRepository.findByStatus(2);
        for (Agent agent : unstableAgents) {
            if (agent.getLastHeartbeat() != null && agent.getLastHeartbeat().isAfter(unstableThreshold)) {
                agent.setStatus(1);
                agentRepository.save(agent);
                log.info("Agent {} recovered to online", agent.getId());
            } else if (agent.getLastHeartbeat() == null || agent.getLastHeartbeat().isBefore(offlineThreshold)) {
                agent.setStatus(0);
                agentRepository.save(agent);
                log.warn("Agent {} marked offline from unstable", agent.getId());
            }
        }
    }

    @Scheduled(cron = "0 0 3 * * ?")
    @Transactional
    public void cleanupOldMetrics() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(30);
        int deleted = metricSnapshotRepository.deleteByRecordedAtBefore(threshold);
        if (deleted > 0) {
            log.info("Cleaned up {} old metric snapshots", deleted);
        }
    }
}
