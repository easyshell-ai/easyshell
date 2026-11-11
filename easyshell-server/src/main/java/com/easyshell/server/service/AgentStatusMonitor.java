package com.easyshell.server.service;

import com.easyshell.server.model.entity.Agent;
import com.easyshell.server.repository.AgentRepository;
import com.easyshell.server.repository.MetricSnapshotRepository;
import com.easyshell.server.websocket.AgentWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final TaskService taskService;
    private final AgentWebSocketHandler agentWebSocketHandler;
    private final HostIdentityService hostIdentityService;
    private final TransactionTemplate transactionTemplate;

    @Scheduled(fixedRate = 30000)
    public void checkAgentStatus() {
        // Scanned entities are only candidates. Never save them after acquiring the lock:
        // deletion, re-registration or a heartbeat may have occurred in the meantime.
        List<String> candidates = java.util.stream.Stream.concat(
                        agentRepository.findByStatus(1).stream(), agentRepository.findByStatus(2).stream())
                .map(Agent::getId).distinct().toList();
        for (String id : candidates) {
            transactionTemplate.executeWithoutResult(status -> updateCurrentStatus(id));
        }
    }

    private void updateCurrentStatus(String id) {
        hostIdentityService.lock(id);
        Agent agent = agentRepository.findById(id).orElse(null);
        if (agent == null || agent.getStatus() == null) return;

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime offlineThreshold = now.minusSeconds(HEARTBEAT_TIMEOUT_SECONDS);
        LocalDateTime unstableThreshold = now.minusSeconds(UNSTABLE_THRESHOLD_SECONDS);
        LocalDateTime heartbeat = agent.getLastHeartbeat();
        int current = agent.getStatus();
        int next = current;
        if (current == 1) {
            if (heartbeat == null || heartbeat.isBefore(offlineThreshold)) {
                if (!agentWebSocketHandler.isAgentConnected(id)) next = 0;
            } else if (heartbeat.isBefore(unstableThreshold)) {
                next = 2;
            }
        } else if (current == 2) {
            if (heartbeat != null && heartbeat.isAfter(unstableThreshold)) {
                next = 1;
            } else if (heartbeat == null || heartbeat.isBefore(offlineThreshold)) {
                next = agentWebSocketHandler.isAgentConnected(id) ? 1 : 0;
            }
        }
        if (next != current) {
            agent.setStatus(next);
            agentRepository.save(agent);
            log.info("Agent {} status changed from {} to {}", id, current, next);
        }
    }

    /**
     * Check for jobs stuck in running/pending state beyond their task timeout.
     * Runs every 30 seconds.
     */
    @Scheduled(fixedRate = 30000)
    public void checkJobTimeouts() {
        try {
            taskService.timeoutStaleJobs();
        } catch (Exception e) {
            log.error("Error checking job timeouts", e);
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
