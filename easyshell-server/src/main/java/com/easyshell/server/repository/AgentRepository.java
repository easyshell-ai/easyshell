package com.easyshell.server.repository;

import com.easyshell.server.model.entity.Agent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AgentRepository extends JpaRepository<Agent, String> {

    List<Agent> findByStatus(Integer status);

    List<Agent> findByStatusAndLastHeartbeatBefore(Integer status, LocalDateTime threshold);

    long countByStatus(Integer status);

    List<Agent> findByStatusOrderByLastHeartbeatDesc(Integer status);
}
