package com.easyshell.server.service;

import com.easyshell.server.model.entity.Agent;
import com.easyshell.server.repository.AgentRepository;
import com.easyshell.server.repository.MetricSnapshotRepository;
import com.easyshell.server.websocket.AgentWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentStatusMonitorTest {
    private final AgentRepository agents = mock(AgentRepository.class);
    private final HostIdentityService identities = mock(HostIdentityService.class);
    private final AgentWebSocketHandler sockets = mock(AgentWebSocketHandler.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final AgentStatusMonitor monitor = new AgentStatusMonitor(agents,
            mock(MetricSnapshotRepository.class), mock(TaskService.class), sockets, identities, transactions);

    @BeforeEach
    void setup() {
        doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactions).executeWithoutResult(any());
        when(agents.findByStatus(2)).thenReturn(List.of());
    }

    @Test
    void staleScannedAgentDoesNotOverwriteRecreatedHost() {
        Agent stale = agent(1, LocalDateTime.now().minusMinutes(5));
        Agent recreated = agent(1, LocalDateTime.now());
        when(agents.findByStatus(1)).thenReturn(List.of(stale));
        when(agents.findById("a")).thenReturn(Optional.of(recreated));
        monitor.checkAgentStatus();
        var order = inOrder(identities, agents);
        order.verify(identities).lock("a");
        order.verify(agents).findById("a");
        verify(agents, never()).save(any());
    }

    @Test
    void deletedAgentIsNotRecreated() {
        when(agents.findByStatus(1)).thenReturn(List.of(agent(1, LocalDateTime.now().minusMinutes(5))));
        when(agents.findById("a")).thenReturn(Optional.empty());
        monitor.checkAgentStatus();
        verify(agents, never()).save(any());
    }

    @Test
    void currentStaleAgentIsMarkedOfflineUnderLock() {
        Agent current = agent(1, LocalDateTime.now().minusMinutes(5));
        when(agents.findByStatus(1)).thenReturn(List.of(current));
        when(agents.findById("a")).thenReturn(Optional.of(current));
        monitor.checkAgentStatus();
        org.junit.jupiter.api.Assertions.assertEquals(0, current.getStatus());
        var order = inOrder(identities, agents);
        order.verify(identities).lock("a");
        order.verify(agents).findById("a");
        order.verify(agents).save(current);
    }

    private Agent agent(int status, LocalDateTime heartbeat) {
        Agent agent = new Agent();
        agent.setId("a");
        agent.setStatus(status);
        agent.setLastHeartbeat(heartbeat);
        return agent;
    }
}
