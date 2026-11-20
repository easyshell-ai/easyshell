package com.easyshell.server.service;

import com.easyshell.server.repository.AgentRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/** Permanent identity ledger. The pair (agentId, generation), never the IP, identifies a host. */
@Service
public class HostIdentityService {
    private final JdbcTemplate jdbc;
    private final AgentRepository agents;

    public HostIdentityService(JdbcTemplate jdbc, AgentRepository agents) {
        this.jdbc = jdbc;
        this.agents = agents;
    }

    /** Acquire before reading or changing an agent; retained until the caller commits. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Identity lock(String agentId) {
        jdbc.update("INSERT INTO host_identity (agent_id, generation, active) VALUES (?, 0, 0) "
                + "ON DUPLICATE KEY UPDATE agent_id = agent_id", agentId);
        return jdbc.queryForObject("SELECT generation, active FROM host_identity WHERE agent_id = ? FOR UPDATE",
                (rs, row) -> new Identity(agentId, rs.getLong(1), rs.getBoolean(2)), agentId);
    }

    /** Called under lock when an agent is present or is being created. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Identity activate(Identity locked) {
        if (locked.active()) return locked;
        long next = Math.addExact(locked.generation(), 1);
        jdbc.update("UPDATE host_identity SET generation = ?, active = 1 WHERE agent_id = ?", next, locked.agentId());
        return new Identity(locked.agentId(), next, true);
    }

    /** Retire without deleting the ledger row. Caller must hold the lock. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void retire(Identity locked) {
        jdbc.update("UPDATE host_identity SET active = 0 WHERE agent_id = ?", locked.agentId());
    }

    /**
     * Backfill existing agents before any ACL migration. Sorted locks avoid batch deadlocks;
     * missing agents are skipped, and repeated calls preserve existing generations.
     * The caller must obtain candidate IDs from the agent table, not from IP addresses.
     */
    @Transactional
    public void initializeExisting(Collection<String> agentIds) {
        List<String> sorted = agentIds.stream().distinct().sorted().toList();
        for (String id : sorted) {
            Identity identity = lock(id);
            if (agents.existsById(id)) activate(identity);
        }
    }

    public record Identity(String agentId, long generation, boolean active) { }
}
