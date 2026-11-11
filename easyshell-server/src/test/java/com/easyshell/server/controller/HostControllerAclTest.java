package com.easyshell.server.controller;

import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.entity.Agent;
import com.easyshell.server.repository.MetricSnapshotRepository;
import com.easyshell.server.repository.ScriptRepository;
import com.easyshell.server.repository.TaskRepository;
import com.easyshell.server.service.AgentService;
import com.easyshell.server.service.HostAclService;
import com.easyshell.server.service.TagService;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HostControllerAclTest {
    @Mock AgentService agents;
    @Mock HostAclService acl;
    @Mock TagService tags;
    @Mock MetricSnapshotRepository metrics;
    @Mock TaskRepository tasks;
    @Mock ScriptRepository scripts;
    @InjectMocks HostController controller;

    private final Authentication auth = UsernamePasswordAuthenticationToken.authenticated(7L, null, List.of());

    @Test
    void listAndDashboardOnlyIncludeVisibleHosts() throws Exception {
        Agent visible = agent("visible", 1, 50.0);
        Agent hidden = agent("hidden", 1, 100.0);
        when(agents.findAll()).thenReturn(List.of(visible, hidden));
        when(acl.allowed(7L, "visible", HostAclService.Permission.VIEW)).thenReturn(true);

        assertEquals(List.of(visible), controller.list(auth).getData());
        var stats = controller.dashboardStats(auth).getData();
        assertEquals(1, stats.getTotalAgents());
        assertEquals(1, stats.getOnlineAgents());
        assertEquals(50.0, stats.getAvgCpuUsage());
        assertEquals(0, stats.getHighCpuAgents());
        assertEquals("visible", stats.getOnlineAgentDetails().get(0).getId());
        assertFalse(stats.isTasksAvailable());
        assertNull(stats.getRecentTasks());
        assertNull(stats.getTotalTasks());
        assertNull(stats.getTodayTasks());
        assertNull(stats.getTodaySuccessTasks());
        assertNull(stats.getTodayFailedTasks());
        assertNull(stats.getTaskSuccessRate());
        assertFalse(stats.isScriptsAvailable());
        assertNull(stats.getTotalScripts());
        verifyNoInteractions(tasks, scripts);
        var json = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL).valueToTree(stats);
        assertTrue(json.has("tasksAvailable"));
        assertFalse(json.get("tasksAvailable").asBoolean());
        for (String field : List.of("totalTasks", "todayTasks", "todaySuccessTasks", "todayFailedTasks", "taskSuccessRate", "recentTasks", "totalScripts")) {
            assertTrue(json.has(field), field);
            assertTrue(json.get(field).isNull(), field);
        }
    }

    @Test
    void deniedDetailTagsAndMetricsNeverReadHostData() {
        assertEquals(403, assertThrows(BusinessException.class, () -> controller.detail("hidden", auth)).getCode());
        assertEquals(403, assertThrows(BusinessException.class, () -> controller.agentTags("hidden", auth)).getCode());
        assertEquals(403, assertThrows(BusinessException.class, () -> controller.getMetrics("hidden", "1h", auth)).getCode());
        verifyNoInteractions(agents, tags, metrics);
    }

    @Test
    void allowedDetailReadsHost() {
        Agent host = agent("visible", 0, 0.0);
        when(acl.allowed(7L, "visible", HostAclService.Permission.VIEW)).thenReturn(true);
        when(agents.findById("visible")).thenReturn(Optional.of(host));
        assertSame(host, controller.detail("visible", auth).getData());
    }

    @Test
    void invalidPrincipalFailsClosed() {
        Authentication forged = UsernamePasswordAuthenticationToken.authenticated("7", null, List.of());
        assertEquals(401, assertThrows(BusinessException.class, () -> controller.list(forged)).getCode());
        assertEquals(401, assertThrows(BusinessException.class, () -> controller.dashboardStats(null)).getCode());
        verifyNoInteractions(agents, acl);
    }

    private static Agent agent(String id, int status, double cpu) {
        Agent agent = new Agent();
        agent.setId(id);
        agent.setStatus(status);
        agent.setCpuUsage(cpu);
        return agent;
    }
}
