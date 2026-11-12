package com.easyshell.server.service;

import com.easyshell.server.repository.AgentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HostIdentityServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AgentRepository agents = mock(AgentRepository.class);
    private final HostIdentityService service = new HostIdentityService(jdbc, agents);

    @Test
    void activeIdentityIsStableAcrossReregistration() {
        var identity = new HostIdentityService.Identity("agent", 4, true);
        assertEquals(identity, service.activate(identity));
        verifyNoInteractions(jdbc);
    }

    @Test
    void recreationAdvancesTombstoneGeneration() {
        var old = new HostIdentityService.Identity("agent", 4, true);
        service.retire(old);
        var recreated = service.activate(new HostIdentityService.Identity("agent", 4, false));
        assertEquals(5, recreated.generation());
        assertTrue(recreated.active());
        verify(jdbc).update("UPDATE host_identity SET active = 0 WHERE agent_id = ?", "agent");
        verify(jdbc).update("UPDATE host_identity SET generation = ?, active = 1 WHERE agent_id = ?", 5L, "agent");
    }

    @Test
    void generationOverflowFailsClosed() {
        assertThrows(ArithmeticException.class,
                () -> service.activate(new HostIdentityService.Identity("agent", Long.MAX_VALUE, false)));
        verifyNoInteractions(jdbc);
    }
}
