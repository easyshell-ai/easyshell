package com.easyshell.server.service;

import com.easyshell.server.model.dto.AgentHeartbeatRequest;
import com.easyshell.server.model.dto.AgentRegisterRequest;
import com.easyshell.server.model.entity.Agent;

import java.util.List;
import java.util.Optional;

public interface AgentService {

    Agent register(AgentRegisterRequest request, String remoteIp);

    void heartbeat(AgentHeartbeatRequest request, String remoteIp);

    Optional<Agent> findById(String agentId);

    List<Agent> findAll();

    long countOnline();

    long countTotal();
}
