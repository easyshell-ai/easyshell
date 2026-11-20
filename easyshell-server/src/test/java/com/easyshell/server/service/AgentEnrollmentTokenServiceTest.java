package com.easyshell.server.service;

import com.easyshell.server.repository.AgentEnrollmentTokenRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentEnrollmentTokenServiceTest {
    @Test
    void issuesRandomBearerButStoresOnlyDigest() {
        var repo = mock(AgentEnrollmentTokenRepository.class);
        var service = new AgentEnrollmentTokenService(repo);
        Instant expiry = Instant.now().plusSeconds(60);
        String first = service.issue("host", 2, 7, expiry);
        String second = service.issue("host", 2, 7, expiry);
        assertNotEquals(first, second);
        assertEquals(43, first.length());
        var hashes = ArgumentCaptor.forClass(String.class);
        verify(repo, times(2)).insert(hashes.capture(), eq("host"), eq(2L), eq(7L), eq(expiry));
        assertTrue(hashes.getAllValues().stream().allMatch(s -> s.matches("[0-9a-f]{64}")));
        assertFalse(hashes.getAllValues().contains(first));
        assertFalse(service.consume("invalid token", "host", 2));
        verify(repo, never()).consume(anyString(), anyString(), anyLong());
    }

    @Test
    void revokesBeforeInsertAndOnlyAfterSuccessfulConsumption() {
        var repo = mock(AgentEnrollmentTokenRepository.class);
        var service = new AgentEnrollmentTokenService(repo);
        Instant expiry = Instant.now().plusSeconds(60);
        String token = service.issue("host", 2, 7, expiry);
        var order = inOrder(repo);
        order.verify(repo).revokeUnconsumed("host");
        order.verify(repo).insert(anyString(), eq("host"), eq(2L), eq(7L), eq(expiry));
        clearInvocations(repo);
        assertFalse(service.consume(token, "host", 2));
        verify(repo, never()).revokeUnconsumed(anyString());
        when(repo.consume(anyString(), eq("host"), eq(2L))).thenReturn(true);
        clearInvocations(repo);
        assertTrue(service.consume(token, "host", 2));
        order = inOrder(repo);
        order.verify(repo).consume(anyString(), eq("host"), eq(2L));
        order.verify(repo).revokeUnconsumed("host");
    }
}
